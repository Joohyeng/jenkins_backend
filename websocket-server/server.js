import http from 'node:http'
import { randomUUID } from 'node:crypto'
import Redis from 'ioredis'
import * as Y from 'yjs'
import * as awarenessProtocol from '@y/protocols/awareness'
import {
  getYDoc,
  setContentInitializor,
  setPersistence,
  setupWSConnection,
} from '@y/websocket-server/utils'
import { WebSocketServer } from 'ws'

const HOST = process.env.HOST || '0.0.0.0'
const PORT = Number.parseInt(process.env.PORT || '1234', 10)
const REDIS_HOST = process.env.REDIS_HOST || 'redis'
const REDIS_PORT = Number.parseInt(process.env.REDIS_PORT || '6379', 10)
const REDIS_PREFIX = process.env.YJS_REDIS_PREFIX || 'wafflebear:yjs'
const SNAPSHOT_SAVE_DELAY_MS = Number.parseInt(
  process.env.YJS_SNAPSHOT_SAVE_DELAY_MS || '150',
  10,
)
const NODE_ID = process.env.POD_NAME || process.env.HOSTNAME || randomUUID()

const REDIS_ORIGIN = Symbol('redis-origin')
const SNAPSHOT_ORIGIN = Symbol('snapshot-origin')

const redisPub = new Redis({
  host: REDIS_HOST,
  port: REDIS_PORT,
})

const redisSub = new Redis({
  host: REDIS_HOST,
  port: REDIS_PORT,
})

const docStates = new Map()

const updateChannel = (docName) => `${REDIS_PREFIX}:update:${encodeURIComponent(docName)}`
const awarenessChannel = (docName) => `${REDIS_PREFIX}:awareness:${encodeURIComponent(docName)}`
const snapshotKey = (docName) => `${REDIS_PREFIX}:snapshot:${encodeURIComponent(docName)}`

const normalizeDocName = (rawValue) => {
  const decoded = decodeURIComponent(String(rawValue || '').trim())
  const normalized = decoded.replace(/^\/+|\/+$/g, '')
  return normalized || 'default'
}

const ensureState = (docName, doc) => {
  let state = docStates.get(docName)
  if (!state) {
    state = {
      docName,
      doc,
      ready: false,
      readyPromise: null,
      snapshotTimer: null,
      pendingMessages: [],
      docUpdateHandler: null,
      awarenessUpdateHandler: null,
      listenersAttached: false,
    }
    docStates.set(docName, state)
  } else {
    state.doc = doc
  }
  return state
}

const serializeEnvelope = (docName, kind, data) => ({
  sourceNode: NODE_ID,
  docName,
  kind,
  data: Buffer.from(data).toString('base64'),
})

const publishEnvelope = async (docName, kind, data) => {
  await redisPub.publish(
    kind === 'awareness' ? awarenessChannel(docName) : updateChannel(docName),
    JSON.stringify(serializeEnvelope(docName, kind, data)),
  )
}

const persistSnapshot = async (state) => {
  if (!state?.doc || !state.doc.name) {
    return
  }

  const encoded = Y.encodeStateAsUpdate(state.doc)
  await redisPub.set(snapshotKey(state.doc.name), Buffer.from(encoded))
}

const scheduleSnapshotSave = (state) => {
  if (!state?.doc || !state.doc.name) {
    return
  }

  if (state.snapshotTimer) {
    clearTimeout(state.snapshotTimer)
  }

  state.snapshotTimer = setTimeout(async () => {
    state.snapshotTimer = null
    try {
      await persistSnapshot(state)
    } catch (error) {
      console.error('[YJS] snapshot save failed', error)
    }
  }, SNAPSHOT_SAVE_DELAY_MS)
}

const attachListeners = (state) => {
  if (!state?.doc || state.listenersAttached) {
    return
  }

  state.docUpdateHandler = (update, origin) => {
    if (origin !== SNAPSHOT_ORIGIN) {
      scheduleSnapshotSave(state)
    }

    if (origin === SNAPSHOT_ORIGIN || origin === REDIS_ORIGIN) {
      return
    }

    void publishEnvelope(state.docName, 'update', update).catch((error) => {
      console.error('[YJS] failed to publish update', error)
    })
  }

  state.awarenessUpdateHandler = ({ added, updated, removed }, origin) => {
    if (origin === SNAPSHOT_ORIGIN || origin === REDIS_ORIGIN) {
      return
    }

    const changedClients = added.concat(updated, removed)
    if (changedClients.length === 0) {
      return
    }

    const encoded = awarenessProtocol.encodeAwarenessUpdate(state.doc.awareness, changedClients)
    void publishEnvelope(state.docName, 'awareness', encoded).catch((error) => {
      console.error('[YJS] failed to publish awareness', error)
    })
  }

  state.doc.on('update', state.docUpdateHandler)
  state.doc.awareness.on('update', state.awarenessUpdateHandler)
  state.listenersAttached = true
}

const applyRedisMessage = (state, envelope) => {
  if (!state?.doc || !envelope) {
    return
  }

  if (envelope.sourceNode === NODE_ID) {
    return
  }

  const payload = new Uint8Array(Buffer.from(envelope.data || '', 'base64'))

  if (envelope.kind === 'awareness') {
    awarenessProtocol.applyAwarenessUpdate(state.doc.awareness, payload, REDIS_ORIGIN)
    return
  }

  Y.applyUpdate(state.doc, payload, REDIS_ORIGIN)
}

const flushPendingMessages = (state) => {
  if (!state?.pendingMessages?.length) {
    return
  }

  const pending = state.pendingMessages.splice(0, state.pendingMessages.length)
  for (const envelope of pending) {
    applyRedisMessage(state, envelope)
  }
}

setContentInitializor(async (doc) => {
  const state = ensureState(doc.name, doc)
  if (state.readyPromise) {
    return state.readyPromise
  }

  state.readyPromise = (async () => {
    try {
      attachListeners(state)

      const snapshot = await redisPub.getBuffer(snapshotKey(doc.name))
      if (snapshot && snapshot.length > 0) {
        Y.applyUpdate(doc, new Uint8Array(snapshot), SNAPSHOT_ORIGIN)
      }

      if (state.pendingMessages.length > 0) {
        flushPendingMessages(state)
      }

      state.ready = true
    } catch (error) {
      console.error(`[YJS] initialization failed for ${doc.name}`, error)
      throw error
    }
  })()

  return state.readyPromise
})

setPersistence({
  bindState: (docName, doc) => {
    ensureState(docName, doc)
  },
  writeState: async (docName, doc) => {
    const state = docStates.get(docName) || ensureState(docName, doc)

    if (state.snapshotTimer) {
      clearTimeout(state.snapshotTimer)
      state.snapshotTimer = null
    }

    if (doc) {
      state.doc = doc
    }

    try {
      if (state.doc) {
        await persistSnapshot(state)
      }
    } finally {
      if (state.listenersAttached && state.docUpdateHandler) {
        state.doc.off('update', state.docUpdateHandler)
      }
      if (state.listenersAttached && state.awarenessUpdateHandler && state.doc?.awareness) {
        state.doc.awareness.off('update', state.awarenessUpdateHandler)
      }
      state.listenersAttached = false
      state.docUpdateHandler = null
      state.awarenessUpdateHandler = null
      state.pendingMessages = []
      state.ready = false
      state.readyPromise = null
      docStates.delete(docName)
    }
  },
})

redisSub.on('pmessageBuffer', (pattern, channel, message) => {
  try {
    const channelName = channel.toString('utf8')
    const rawDocName = channelName.slice(channelName.lastIndexOf(':') + 1)
    const docName = normalizeDocName(rawDocName)
    const state = docStates.get(docName)
    if (!state) {
      return
    }

    const envelope = JSON.parse(message.toString('utf8'))
    if (envelope.sourceNode === NODE_ID) {
      return
    }

    if (!state.ready) {
      state.pendingMessages.push(envelope)
      return
    }

    applyRedisMessage(state, envelope)
  } catch (error) {
    console.error('[YJS] failed to handle redis message', error)
  }
})

await redisSub.psubscribe(
  `${REDIS_PREFIX}:update:*`,
  `${REDIS_PREFIX}:awareness:*`,
)

const handleConnection = async (ws, req) => {
  const docName = normalizeDocName((req.url || '').slice(1).split('?')[0])
  const doc = getYDoc(docName, true)

  try {
    await doc.whenInitialized
    setupWSConnection(ws, req, { docName, gc: true })
  } catch (error) {
    console.error(`[YJS] failed to initialize doc ${docName}`, error)
    try {
      ws.close()
    } catch (closeError) {
      console.error('[YJS] websocket close failed', closeError)
    }
  }
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' })
  res.end('WaffleBear Yjs websocket server')
})

const wss = new WebSocketServer({ noServer: true })

wss.on('connection', (ws, req) => {
  void handleConnection(ws, req)
})

server.on('upgrade', (request, socket, head) => {
  wss.handleUpgrade(request, socket, head, (ws) => {
    wss.emit('connection', ws, request)
  })
})

server.listen(PORT, HOST, () => {
  console.log(`Yjs websocket server listening on ${HOST}:${PORT}`)
})
