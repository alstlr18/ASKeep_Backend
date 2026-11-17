package com.GDGoCSMU.ASKeep.domain;

import com.GDGoCSMU.ASKeep.domain.material.MaterialRepository;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 발표자료 원본 PDF 다운로드 (아카이브) */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:askeep-material-download;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class MaterialDownloadTest {

    static final byte[] PDF = "%PDF-1.7\n다운로드 테스트용 PDF".getBytes(StandardCharsets.UTF_8);
    static final String FILE_NAME = "1주차 발표자료.pdf";
    static final HttpServer fakeAi = startFakeAi();
    static final String uploadDir = tempDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai.server.base-url", () -> "http://localhost:" + fakeAi.getAddress().getPort());
        registry.add("askeep.upload-dir", () -> uploadDir);
    }

    @AfterAll
    static void stopFakeAi() {
        fakeAi.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MaterialRepository materialRepository;

    @Test
    void 종료된_세션_자료는_로그인한_누구나_받고_종료_전에는_참여자만_받는다() throws Exception {
        String presenter = signupAndLogin();
        String audience = signupAndLogin();
        String stranger = signupAndLogin();   // 세션에 참여하지 않은 사람
        long sessionId = createSession(presenter);
        long materialId = uploadPdf(presenter, sessionId);
        join(audience, sessionId, presenter);
        String url = "/api/v1/materials/" + materialId + "/download";
        String listUrl = "/api/v1/sessions/" + sessionId + "/materials";

        // 시작 전(READY): 발표자·참여자만
        mvc.perform(get(url).header("Authorization", "Bearer " + audience)).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());
        mvc.perform(get(listUrl).header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());

        // 진행 중(ONGOING): 여전히 미참여자는 불가 (입장 코드 없이 자료가 새지 않게)
        mvc.perform(post("/api/v1/sessions/" + sessionId + "/start").header("Authorization", "Bearer " + presenter))
                .andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());
        mvc.perform(get(listUrl).header("Authorization", "Bearer " + stranger)).andExpect(status().isForbidden());

        // 종료(ENDED) → 아카이브: 로그인한 누구나 목록 조회·다운로드 가능
        mvc.perform(post("/api/v1/sessions/" + sessionId + "/end").header("Authorization", "Bearer " + presenter))
                .andExpect(status().isOk());

        mvc.perform(get(listUrl).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(materialId))
                .andExpect(jsonPath("$.data.items[0].fileName").value(FILE_NAME));

        String encodedName = URLEncoder.encode(FILE_NAME, StandardCharsets.UTF_8).replace("+", "%20");
        for (String token : new String[]{presenter, audience, stranger}) {
            mvc.perform(get(url).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, PDF.length))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                            containsString("filename*=UTF-8''" + encodedName)))
                    .andExpect(content().bytes(PDF));
        }

        // 다운로드 외 기능(슬라이드 뷰어)은 종료 후에도 여전히 참여자 전용
        mvc.perform(get("/api/v1/materials/" + materialId + "/pages").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isForbidden());

        // 로그인 안 하면 불가
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(listUrl)).andExpect(status().isUnauthorized());

        // 없는 자료
        mvc.perform(get("/api/v1/materials/999999/download").header("Authorization", "Bearer " + audience))
                .andExpect(status().isNotFound());

        // 서버에서 원본 파일이 사라진 경우
        Files.delete(Path.of(materialRepository.findById(materialId).orElseThrow().getFilePath()));
        mvc.perform(get(url).header("Authorization", "Bearer " + audience))
                .andExpect(status().isGone());
    }

    @Test
    void 브라우저가_파일명_헤더를_읽을_수_있게_CORS로_노출한다() throws Exception {
        String presenter = signupAndLogin();
        long sessionId = createSession(presenter);
        long materialId = uploadPdf(presenter, sessionId);

        mvc.perform(get("/api/v1/materials/" + materialId + "/download")
                        .header("Authorization", "Bearer " + presenter)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.CONTENT_DISPOSITION)));
    }

    // --- helpers ---

    private long uploadPdf(String token, long sessionId) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", FILE_NAME, "application/pdf", PDF);
        String body = mvc.perform(multipart("/api/v1/sessions/" + sessionId + "/materials").file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Integer) JsonPath.read(body, "$.data.id")).longValue();
    }

    private String signupAndLogin() throws Exception {
        String email = UUID.randomUUID() + "@test.com";
        String name = "dl-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/v1/users/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\",\"name\":\"%s\",\"department\":\"컴퓨터과학과\"}"
                                .formatted(email, name)))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/users/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private long createSession(String token) throws Exception {
        String body = mvc.perform(post("/api/v1/sessions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"다운로드 테스트 세션\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Integer) JsonPath.read(body, "$.data.sessionId")).longValue();
    }

    private void join(String token, long sessionId, String presenterToken) throws Exception {
        String session = mvc.perform(get("/api/v1/sessions/" + sessionId).header("Authorization", "Bearer " + presenterToken))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(session, "$.data.entryCode");
        mvc.perform(post("/api/v1/sessions/participants").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"entryCode\":\"%s\"}".formatted(code)))
                .andExpect(status().isCreated());
    }

    private static String tempDir() {
        try {
            return Files.createTempDirectory("askeep-download-test").toString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 자료 업로드 시 호출되는 AI 서버를 대신하는 가짜 서버 (다운로드 자체는 AI 서버를 쓰지 않는다) */
    private static HttpServer startFakeAi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = "{\"materialId\":1,\"status\":\"success\",\"chunkCount\":1,\"pageCount\":1}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
