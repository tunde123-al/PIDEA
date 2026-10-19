package com.pidea.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pidea.common.ValidationException;
import com.pidea.integration.SourceType;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@Component
public class UploadFileValidator {
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_RECORDS = 100_000;

    private final ObjectMapper mapper;

    public UploadFileValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ValidatedFile validate(MultipartFile file, SourceType sourceType) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Upload file must not be empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new MaxUploadSizeExceededException(MAX_FILE_BYTES);
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new ValidationException("Upload filename must end in .csv or .json");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        SourceType expected;
        String extension;
        if (lower.endsWith(".csv")) {
            expected = SourceType.CSV_FILE;
            extension = "csv";
        } else if (lower.endsWith(".json")) {
            expected = SourceType.JSON_FILE;
            extension = "json";
        } else {
            throw new ValidationException("Only .csv and .json uploads are supported");
        }
        if (sourceType != expected) {
            throw new ValidationException("Upload format does not match the integration source type");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new ValidationException("Upload could not be read");
        }
        String text = decodeUtf8(content);
        int recordCount = extension.equals("csv") ? countCsvRecords(text) : countJsonRecords(text);
        if (recordCount == 0) {
            throw new ValidationException("Upload must contain at least one data record");
        }
        if (recordCount > MAX_RECORDS) {
            throw new ValidationException("Upload exceeds the 100000 record limit");
        }
        return new ValidatedFile(extension, recordCount, content);
    }

    private static String decodeUtf8(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
            && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException e) {
            throw new ValidationException("Upload must use UTF-8 encoding");
        }
    }

    private static int countCsvRecords(String text) {
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .get()
                .parse(new StringReader(text))) {
            Set<String> headers = new HashSet<>();
            for (String header : parser.getHeaderNames()) {
                if (header == null || header.isBlank() || !headers.add(header)) {
                    throw new ValidationException("CSV headers must be non-empty and unique");
                }
            }
            if (headers.isEmpty()) {
                throw new ValidationException("CSV must contain a header row");
            }
            long count = 0;
            for (var ignored : parser) {
                if (++count > MAX_RECORDS) {
                    return MAX_RECORDS + 1;
                }
            }
            return (int) count;
        } catch (IOException | IllegalArgumentException e) {
            throw new ValidationException("CSV file is malformed");
        }
    }

    private int countJsonRecords(String text) {
        try {
            JsonNode root = mapper.readTree(text);
            JsonNode records = root != null && root.isObject() ? root.get("records") : root;
            if (records == null || !records.isArray()) {
                throw new ValidationException("JSON must be an array or an object with a records array");
            }
            return records.size();
        } catch (IOException e) {
            throw new ValidationException("JSON file is malformed");
        }
    }

    public record ValidatedFile(String extension, int recordCount, byte[] content) {}
}
