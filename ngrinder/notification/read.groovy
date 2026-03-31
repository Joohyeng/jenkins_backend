package ngrinder.notification.read

import static net.grinder.script.Grinder.grinder
import org.junit.Test
import org.junit.runner.RunWith
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread

@RunWith(GrinderRunner)
class TestRunner {
    protected static String baseUrl = System.getProperty('baseUrl', 'http://192.100.220.17:8080/notification')
    protected static String loginEmail = System.getProperty('loginEmail', 'administrator@administrator.adm')
    protected static String loginPassword = System.getProperty('loginPassword', 'fweiuhfge2232n12@#xSD23@')

    protected static net.grinder.script.GTest test
    protected static org.ngrinder.http.HTTPRequest request

    protected String accessToken
    protected String refreshToken

    static void initProcess(String testName) {
        test = new net.grinder.script.GTest(1, testName)
        request = new org.ngrinder.http.HTTPRequest()
    }

    protected void login() {
        org.ngrinder.http.HTTPResponse response = request.POST(fullUrl('/login').toString(), [
                email   : loginEmail,
                password: loginPassword
        ])

        assertStatus(response, [200])

        Map loginResult = new groovy.json.JsonSlurper().parseText(response.getBodyText()) as Map
        accessToken = loginResult.accessToken as String
        refreshToken = extractRefreshToken(response)

        assert accessToken : 'Login response did not include accessToken.'
        assert refreshToken : 'Login response did not include refresh cookie.'
    }

    protected String fullUrl(String path) {
        if (path == null || path.isBlank()) {
            return baseUrl
        }
        if (path.startsWith('http://') || path.startsWith('https://')) {
            return path
        }

        java.net.URI baseUri = java.net.URI.create(baseUrl)
        String origin = "${baseUri.scheme}://${baseUri.authority}"
        String basePath = baseUri.path ?: ''
        String normalizedPath = path.startsWith('/') ? path : "/${path}"

        if (normalizedPath == '/login'
                || normalizedPath.startsWith('/oauth2')
                || normalizedPath.startsWith('/login/oauth2')) {
            return "${origin}${normalizedPath}".toString()
        }

        if (basePath == null || basePath.isBlank() || normalizedPath.startsWith(basePath)) {
            return "${origin}${normalizedPath}".toString()
        }

        return "${baseUrl}${normalizedPath}".toString()
    }

    protected Map<String, String> headers(Map<String, String> base = [:], Map<String, String> extra = [:]) {
        Map<String, String> merged = [:]
        merged.putAll(base ?: [:])
        merged.putAll(extra ?: [:])
        return merged
    }

    protected Map<String, String> authHeaders(Map<String, String> extra = [:]) {
        Map<String, String> base = [:]
        if (accessToken != null && !accessToken.isBlank()) {
            base['ATOKEN'] = "Bearer ${accessToken}".toString()
        }
        return headers(base, extra)
    }

    protected Map<String, String> refreshHeaders(Map<String, String> extra = [:]) {
        Map<String, String> base = authHeaders()
        if (refreshToken != null && !refreshToken.isBlank()) {
            base.Cookie = "refresh=${refreshToken}".toString()
        }
        return headers(base, extra)
    }

    protected Map<String, String> jsonHeaders(Map<String, String> extra = [:]) {
        return headers(authHeaders(), headers(['Content-Type': 'application/json; charset=UTF-8'], extra))
    }

    protected Map<String, String> refreshJsonHeaders(Map<String, String> extra = [:]) {
        return headers(refreshHeaders(), headers(['Content-Type': 'application/json; charset=UTF-8'], extra))
    }

    protected org.ngrinder.http.HTTPResponse get(String path, Map params = [:], Map<String, String> requestHeaders = null) {
        return request.GET(fullUrl(path).toString(), params ?: [:], requestHeaders ?: authHeaders())
    }

    protected org.ngrinder.http.HTTPResponse postJson(String path, Object body = [:], Map<String, String> requestHeaders = null) {
        return request.POST(
                fullUrl(path).toString(),
                jsonBytes(body),
                requestHeaders ?: jsonHeaders()
        )
    }

    protected org.ngrinder.http.HTTPResponse postEmpty(String path, Map<String, String> requestHeaders = null) {
        return request.POST(fullUrl(path).toString(), new byte[0], requestHeaders ?: authHeaders())
    }

    protected org.ngrinder.http.HTTPResponse putJson(String path, Object body = [:], Map<String, String> requestHeaders = null) {
        return request.PUT(
                fullUrl(path).toString(),
                jsonBytes(body),
                requestHeaders ?: jsonHeaders()
        )
    }

    protected org.ngrinder.http.HTTPResponse patchJson(String path, Object body = [:], Map<String, String> requestHeaders = null) {
        return request.PATCH(
                fullUrl(path).toString(),
                jsonBytes(body),
                requestHeaders ?: jsonHeaders()
        )
    }

    protected org.ngrinder.http.HTTPResponse deleteReq(String path, Map params = [:], Map<String, String> requestHeaders = null) {
        return request.DELETE(fullUrl(path).toString(), params ?: [:], requestHeaders ?: authHeaders())
    }

    protected Map<String, Object> multipartPayload(List<Map<String, Object>> parts) {
        String boundary = "----ngrinder-${java.util.UUID.randomUUID().toString().replace('-', '')}".toString()
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()

        parts.each { Map<String, Object> part ->
            out.write(("--${boundary}\r\n".toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8))

            String disposition = "Content-Disposition: form-data; name=\"${part.name}\"".toString()
            if (part.filename != null) {
                disposition += "; filename=\"${part.filename}\"".toString()
            }
            out.write((disposition + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))

            if (part.contentType != null) {
                out.write(("Content-Type: ${part.contentType}\r\n".toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            }

            out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))

            if (part.bytes instanceof byte[]) {
                out.write((byte[]) part.bytes)
            } else if (part.text != null) {
                out.write(part.text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            } else if (part.value != null) {
                out.write(part.value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
            }

            out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }

        out.write(("--${boundary}--\r\n".toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        return [boundary: boundary, bytes: out.toByteArray()]
    }

    protected Map<String, String> multipartHeaders(String boundary, Map<String, String> extra = [:]) {
        return headers(authHeaders(), headers(['Content-Type': "multipart/form-data; boundary=${boundary}".toString()], extra))
    }

    protected byte[] samplePngBytes() {
        return java.util.Base64.decoder.decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/LJkAAAAASUVORK5CYII=')
    }

    protected byte[] textBytes(String value) {
        return (value ?: '').getBytes(java.nio.charset.StandardCharsets.UTF_8)
    }

    protected long propLong(String key, long defaultValue) {
        String raw = System.getProperty(key)
        if (raw == null || raw.isBlank()) {
            return defaultValue
        }
        try {
            return Long.parseLong(raw.trim())
        } catch (Exception ignored) {
            return defaultValue
        }
    }

    protected int propInt(String key, int defaultValue) {
        String raw = System.getProperty(key)
        if (raw == null || raw.isBlank()) {
            return defaultValue
        }
        try {
            return Integer.parseInt(raw.trim())
        } catch (Exception ignored) {
            return defaultValue
        }
    }

    protected String prop(String key, String defaultValue) {
        String raw = System.getProperty(key)
        return raw == null || raw.isBlank() ? defaultValue : raw
    }

    protected void assertStatus(org.ngrinder.http.HTTPResponse response, List<Integer> allowedStatuses = [200]) {
        int status = response.getStatusCode()
        assert allowedStatuses.contains(status) : "Unexpected status ${status}. Body: ${safeBody(response)}".toString()
    }

    protected String safeBody(org.ngrinder.http.HTTPResponse response) {
        String body = response.getBodyText()
        if (body == null) {
            return ''
        }
        return body.length() > 512 ? body.substring(0, 512) + '...' : body
    }

    protected String extractRefreshToken(org.ngrinder.http.HTTPResponse response) {
        def cookieHeaders = response.getHeaders('Set-Cookie')
        if (cookieHeaders == null) {
            return null
        }

        for (def header : cookieHeaders) {
            String value = header.getValue()
            if (value == null) {
                continue
            }
            def matcher = value =~ /refresh=([^;]+)/
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private byte[] jsonBytes(Object body) {
        String json = groovy.json.JsonOutput.toJson(body)
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    }
    @BeforeProcess
    static void beforeProcess() {
        initProcess("notification-read")
    }

    @BeforeThread
    void beforeThread() {
        grinder.statistics.delayReports = true

        login()

    }

    @Test
    void test() {
        def response = patchJson('/notification/read', [
                id  : propLong('notificationTargetId', 1L),
                uuid: prop('notificationUuid', 'REPLACE_WITH_NOTIFICATION_UUID')
        ])
        assertStatus(response)
    }
}

