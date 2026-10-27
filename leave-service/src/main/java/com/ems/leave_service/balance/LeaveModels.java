package com.ems.leave_service.balance;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class LeaveModels {
    private LeaveModels() {}

    public record Type(UUID id, String code, String name, boolean active) {}

    public record TypeInput(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,39}$")
            String code,

            @NotBlank @Size(max = 100) String name,
            @NotNull Boolean active) {}

    public record Allocation(
            @Positive long employeeId,
            @NotNull UUID typeId,
            @Min(1900) @Max(9999) int year,

            @NotNull @DecimalMin("0.00") @Digits(integer = 7, fraction = 2)
            BigDecimal days,

            @NotBlank @Size(max = 500) String reason) {}

    public record Adjustment(
            @NotNull @Digits(integer = 7, fraction = 2) BigDecimal days,
            @NotBlank @Size(max = 100) String idempotencyKey,
            @NotBlank @Size(max = 500) String reason) {}
    // Decimal strings deliberately preserve fixed precision across JavaScript clients.
    public record Balance(
            UUID id,
            long employeeId,
            UUID typeId,
            String typeName,
            int year,
            String entitlement,
            String adjustments,
            String taken,
            String reserved,
            String available) {}

    public record Entry(
            UUID id,
            UUID entitlementId,
            String kind,
            String days,
            String idempotencyKey,
            long actorId,
            String reason,
            Instant createdAt) {}
}
