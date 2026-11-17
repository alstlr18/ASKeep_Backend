package com.GDGoCSMU.ASKeep.domain.material;

import com.GDGoCSMU.ASKeep.domain.common.CurrentUser;
import com.GDGoCSMU.ASKeep.domain.common.PageResponse;
import com.GDGoCSMU.ASKeep.global.common.ApiResponse;
import com.GDGoCSMU.ASKeep.domain.material.dto.MaterialPagesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService materialService;

    @PostMapping("/sessions/{sessionId}/materials")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<MaterialResponse> upload(@PathVariable Long sessionId, @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(MaterialResponse.from(materialService.upload(sessionId, CurrentUser.id(), file)));
    }

    @GetMapping("/sessions/{sessionId}/materials")
    ApiResponse<PageResponse<MaterialResponse>> list(@PathVariable Long sessionId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return ApiResponse.ok(PageResponse.from(materialService.list(sessionId, CurrentUser.id(), PageRequest.of(page, size)), MaterialResponse::from));
    }

    @GetMapping("/materials/{materialId}")
    ApiResponse<MaterialResponse> get(@PathVariable Long materialId) {
        return ApiResponse.ok(MaterialResponse.from(materialService.get(materialId, CurrentUser.id())));
    }

    @DeleteMapping("/materials/{materialId}")
    ApiResponse<Void> delete(@PathVariable Long materialId) {
        materialService.delete(materialId, CurrentUser.id());
        return ApiResponse.ok();
    }

    @GetMapping("/materials/{materialId}/pages")
    ApiResponse<MaterialPagesResponse> pages(@PathVariable Long materialId) {
        return ApiResponse.ok(materialService.pages(materialId, CurrentUser.id()));
    }

    @GetMapping(value = "/materials/{materialId}/pages/{pageNumber}/image", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> pageImage(@PathVariable Long materialId, @PathVariable int pageNumber) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .body(materialService.pageImage(materialId, CurrentUser.id(), pageNumber));
    }

    /** 발표자료 원본 PDF 다운로드 (종료된 세션은 로그인한 누구나, 진행 전·중에는 발표자·참여자만) */
    @GetMapping("/materials/{materialId}/download")
    ResponseEntity<Resource> download(@PathVariable Long materialId) throws IOException {
        Material material = materialService.downloadable(materialId, CurrentUser.id());
        Path file = Path.of(material.getFilePath());
        // 한글 파일명이 깨지지 않도록 filename*=UTF-8''... 형식으로 내려준다
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(material.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 파일을 메모리에 한 번에 올리지 않고 스트리밍으로 보낸다
                .body(new FileSystemResource(file));
    }

    @PostMapping("/materials/{materialId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<MaterialResponse> retry(@PathVariable Long materialId) {
        return ApiResponse.ok(MaterialResponse.from(materialService.retry(materialId, CurrentUser.id())));
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page/size 범위가 올바르지 않습니다.");
    }
}
