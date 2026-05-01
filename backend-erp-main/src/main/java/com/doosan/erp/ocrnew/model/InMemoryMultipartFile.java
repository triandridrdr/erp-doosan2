package com.doosan.erp.ocrnew.model;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.lang.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class InMemoryMultipartFile implements MultipartFile {

    private final String originalFilename;
    private final String contentType;
    @NonNull
    private final byte[] bytes;

    public InMemoryMultipartFile(String originalFilename, String contentType, byte[] bytes) {
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    @Override
    @NonNull
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes.length;
    }

    @Override
    @NonNull
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    @NonNull
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(@NonNull java.io.File dest) throws IOException, IllegalStateException {
        throw new UnsupportedOperationException("InMemoryMultipartFile does not support transferTo");
    }
}
