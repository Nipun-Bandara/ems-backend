package com.ems.claim_service;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/claims/{claim}/receipts")
public class ReceiptController {
    private final ReceiptService service;

    public ReceiptController(ReceiptService service) {
        this.service = service;
    }

    @GetMapping
    public List<ReceiptService.Receipt> list(@PathVariable UUID claim, Authentication a) {
        return service.list(claim, Long.parseLong(a.getName()));
    }

    @PostMapping("/upload-intents")
    public ReceiptService.Intent intent(
            @PathVariable UUID claim, Authentication a, @Valid @RequestBody ReceiptService.Upload input) {
        return service.intent(claim, Long.parseLong(a.getName()), input);
    }

    @PutMapping("/{id}/finalize")
    public ReceiptService.Receipt finalizeUpload(@PathVariable UUID claim, @PathVariable UUID id, Authentication a) {
        return service.finalizeUpload(claim, id, Long.parseLong(a.getName()));
    }

    @GetMapping("/{id}/download")
    public ReceiptService.Download download(@PathVariable UUID claim, @PathVariable UUID id, Authentication a) {
        return service.download(claim, id, Long.parseLong(a.getName()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID claim, @PathVariable UUID id, Authentication a) {
        service.delete(claim, id, Long.parseLong(a.getName()));
    }
}
