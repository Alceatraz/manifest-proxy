package studio.blacktech.manifestproxy.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kotlin.Pair;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ManifestProxy implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(ManifestProxy.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofMinutes(10))
        .writeTimeout(Duration.ofMinutes(10))
        .build();

    @Override
    public void init(FilterConfig filterConfig) {
        logger.info("ManifestProxy filter loaded");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException {

        if (!(servletRequest instanceof HttpServletRequest httpServletRequest)) return;
        if (!(servletResponse instanceof HttpServletResponse httpServletResponse)) return;

        //= ============================================================================================================
        //= Parse all headers

        Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
        Map<String, String> requestHeaders = new LinkedHashMap<>();

        while (headerNames.hasMoreElements()) {
            String nextElement = headerNames.nextElement();
            String header = httpServletRequest.getHeader(nextElement);
            requestHeaders.put(nextElement, header);
        }

        //= ============================================================================================================
        //= Extract config from headers

        String configServerAddress = Objects.requireNonNullElse(requestHeaders.remove("OCI-Server"), "http://127.0.0.1:8081");
        String configRegistryGroup = Objects.requireNonNullElse(requestHeaders.remove("OCI-Registry-Group"), "docker-group");
        String configRegistryHosted = Objects.requireNonNullElse(requestHeaders.remove("OCI-Registry-Hosted"), "docker-hosted");

        Headers headersOf = Headers.of(requestHeaders);

        //= ============================================================================================================
        //= Check URI

        String requestURI = httpServletRequest.getRequestURI();

        Pattern pattern = Pattern.compile("^.*?/repository/" + configRegistryHosted + "/v2/(?<name>.*?)/manifests/(?<tag>.*)");

        Matcher matcher = pattern.matcher(requestURI);

        if (!matcher.find()) throw new RuntimeException("ERROR: Request URI not a valid OCI manifest put action.");

        String name = matcher.group("name");
        String tag = matcher.group("tag");

        String requestBaseGroupURL = configServerAddress + "/repository/" + configRegistryGroup;
        String requestBaseHostedURL = configServerAddress + "/repository/" + configRegistryHosted;

        //= ============================================================================================================
        //= Parse manifest

        byte[] manifestBytes = httpServletRequest.getInputStream().readAllBytes();
        String manifest = new String(manifestBytes, StandardCharsets.UTF_8);

        //= ============================================================================================================
        //= Ensure layer

        for (String digest : parseManifestLayers(manifest)) {
            ensureLayerBlog(headersOf, name, digest, requestBaseGroupURL, requestBaseHostedURL);
        }

        //= ============================================================================================================
        //= Upload manifest

        String requestManifestPutURL = requestBaseHostedURL + "/v2/" + name + "/manifests/" + tag;

        Response uploadManifest = uploadManifest(headersOf, requestManifestPutURL, manifestBytes);

        httpServletResponse.setStatus(uploadManifest.code());

        Headers pairs = uploadManifest.headers();
        for (Pair<? extends String, ? extends String> pair : pairs) {
            httpServletResponse.setHeader(pair.getFirst(), pair.getSecond());
        }

        byte[] body = Objects.requireNonNull(uploadManifest.body()).bytes();
        uploadManifest.close();

        ServletOutputStream outputStream = httpServletResponse.getOutputStream();
        outputStream.write(body);
        outputStream.flush();

        System.out.println("[" + name + "] PUT  <" + tag + "> " + new String(body, StandardCharsets.UTF_8));
    }


    private void ensureLayerBlog(Headers headersOf, String name, String digest, String requestBaseGroupURL, String requestBaseHostedURL) throws IOException {

        String requestLayerHeadURL = requestBaseHostedURL + "/v2/" + name + "/blobs/" + digest;

        Request build = new Request.Builder().url(requestLayerHeadURL).headers(headersOf).head().build();

        int codeHead;

        try (Response response = client.newCall(build).execute()) {
            codeHead = response.code();
        }

        System.out.println("[" + name + "] HEAD <" + digest + "> " + codeHead);

        if (codeHead == 200) return;

        //= ============================================================================================================

        String requestBlobPostURL = requestBaseHostedURL + "/v2/" + name + "/blobs/uploads/";

        Request postRequest = new Request.Builder().url(requestBlobPostURL).headers(headersOf).post(RequestBody.create(new byte[0])).build();

        String uuid;

        try (Response response = client.newCall(postRequest).execute()) {
            uuid = response.header("Docker-Upload-UUID");
        }

        if (uuid == null) throw new RuntimeException("Docker-Upload-UUID not exist");

        System.out.println("[" + name + "] POST <" + digest + "> uuid=" + uuid);

        //= ============================================================================================================

        String requestLayerGetURL = requestBaseGroupURL + "/v2/" + name + "/blobs/" + digest;
        String requestLayerPutURL = requestBaseHostedURL + "/v2/" + name + "/blobs/uploads/" + uuid + "?digest=" + digest;

        Request requestLayerGet = new Request.Builder().url(requestLayerGetURL).headers(headersOf).get().build();

        long a = System.currentTimeMillis();

        Response responseLayerGet = client.newCall(requestLayerGet).execute();

        InputStream inputStream = Objects.requireNonNull(responseLayerGet.body()).byteStream();

        AtomicInteger size = new AtomicInteger(0);

        Request putRequest = new Request.Builder().url(requestLayerPutURL + "").headers(headersOf).put(new RequestBody() {

            @Nullable
            @Override
            public MediaType contentType() {
                return null;
            }

            @Override
            public void writeTo(@NotNull BufferedSink bufferedSink) throws IOException {
                int length;
                while (true) {
                    byte[] buffer = new byte[1024];
                    length = inputStream.read(buffer);
                    if (length < 0) break;
                    bufferedSink.write(buffer, 0, length);
                    size.addAndGet(length);
                }
            }
        }).build();

        Response responseLayerPut = client.newCall(putRequest).execute();

        responseLayerGet.close();
        responseLayerPut.close();

        int codePut = responseLayerPut.code();

        if (codePut != 201) {
            throw new RuntimeException("PUT response code not 201 -> " + Objects.requireNonNull(responseLayerPut.body()).string());
        }

        long b = System.currentTimeMillis();

        System.out.println("[" + name + "] COPY <" + digest + "> uuid=" + uuid + " time=" + (b - a) + "ms size=" + size.get());

    }

    private Response uploadManifest(Headers headersOf, String requestURL, byte[] manifest) throws IOException {
        Request putRequest = new Request.Builder().url(requestURL).headers(headersOf).put(RequestBody.create(manifest)).build();
        return client.newCall(putRequest).execute();
    }


    private List<String> parseManifestLayers(String manifest) throws JsonProcessingException {
        List<String> digests = new LinkedList<>();
        JsonNode jsonNode = objectMapper.readTree(manifest);
        JsonNode layers = jsonNode.get("layers");
        Iterator<JsonNode> elements = layers.elements();
        while (elements.hasNext()) {
            JsonNode next = elements.next();
            JsonNode digest = next.get("digest");
            String text = digest.asText();
            digests.add(text);
        }
        return digests;
    }
}
