package com.example.auctions.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Controller
public class ImageController {

    private static final Map<String, MediaType> MEDIA_TYPES = new HashMap<>();
    static {
        MEDIA_TYPES.put(".jpg", MediaType.IMAGE_JPEG);
        MEDIA_TYPES.put(".jpeg", MediaType.IMAGE_JPEG);
        MEDIA_TYPES.put(".png", MediaType.IMAGE_PNG);
        MEDIA_TYPES.put(".gif", MediaType.IMAGE_GIF);
        MEDIA_TYPES.put(".webp", MediaType.parseMediaType("image/webp"));
        MEDIA_TYPES.put(".bmp", MediaType.parseMediaType("image/bmp"));
    }

    @Value("${auction.images.upload.path}")
    private String uploadPath;

    @GetMapping("/images/auctions/{filename:.+}")
    @ResponseBody
    public ResponseEntity<Resource> serveFile(@PathVariable String filename) {
        try {
            // Normalize paths and verify file stays within upload directory
            Path uploadRoot = Paths.get(uploadPath).toAbsolutePath().normalize();
            Path file = uploadRoot.resolve(filename).normalize();
            if (!file.startsWith(uploadRoot)) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new UrlResource(file.toUri());

            if (resource.exists() && resource.isReadable()) {
                // Get file extension safely
                String extension = "";
                if (filename.contains(".")) {
                    extension = filename.substring(filename.lastIndexOf(".")).toLowerCase();
                }
                // Get media type based on extension, default to JPEG if unknown
                MediaType mediaType = MEDIA_TYPES.getOrDefault(extension, MediaType.IMAGE_JPEG);

                return ResponseEntity.ok()
                    .contentType(mediaType)
                    .body(resource);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (MalformedURLException e) {
            return ResponseEntity.notFound().build();
        }
    }
} 