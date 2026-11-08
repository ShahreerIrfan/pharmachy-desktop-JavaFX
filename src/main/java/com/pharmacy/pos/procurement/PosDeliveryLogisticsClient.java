package com.pharmacy.pos.procurement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * PosDeliveryLogisticsClient
 * Architecture Decision: ADR-0196 (P196).
 * Phase 10: Suppliers, Purchase Orders & Supply Chain.
 *
 * Desktop JavaFX client governing Delivery and Logistics Tracking:
 * - Tracks shipment lifecycles from depot dispatch to branch gate delivery.
 * - Carrier consignment, vehicle plates, driver contacts, and live ETAs.
 * - Cold-chain temperature telemetry and excursion breach detection.
 * - Branch receiving confirmation, package counts, and discrepancy reports.
 */
public class PosDeliveryLogisticsClient {

    private final String tenantId;
    private final String branchId;
    private final String userRole;
    private final String userId;
    private final boolean isOffline;
    private final Map<String, ProcurementShipmentDto> shipmentStore;

    private static final Set<String> ALLOWED_PROCUREMENT_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "PROCUREMENT_OFFICER", "INVENTORY_CLERK", "AUDITOR"
    );

    private static final Set<String> ALLOWED_RECEIVING_ROLES = Set.of(
            "BRANCH_MANAGER", "PHARMACY_OWNER", "SUPERADMIN", "STAFF_PHARMACIST", "INVENTORY_CLERK", "PROCUREMENT_OFFICER"
    );

    public PosDeliveryLogisticsClient(
            String tenantId,
            String branchId,
            String userRole,
            String userId,
            boolean isOffline,
            Map<String, ProcurementShipmentDto> shipmentStore) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.branchId = branchId != null ? branchId : "";
        this.userRole = userRole != null ? userRole : "CASHIER";
        this.userId = userId != null ? userId : "user_anon";
        this.isOffline = isOffline;
        this.shipmentStore = shipmentStore != null ? shipmentStore : new HashMap<>();
    }

    public static final class ShipmentEventDto {
        public final String eventId;
        public final String stage;
        public final String locationName;
        public final String notes;
        public final String recordedBy;
        public final Instant recordedAt;

        public ShipmentEventDto(String eventId, String stage, String locationName, String notes, String recordedBy, Instant recordedAt) {
            this.eventId = eventId != null ? eventId : "she-" + UUID.randomUUID().toString().substring(0, 8);
            this.stage = stage;
            this.locationName = locationName != null ? locationName : "";
            this.notes = notes != null ? notes : "";
            this.recordedBy = recordedBy != null ? recordedBy : "";
            this.recordedAt = recordedAt != null ? recordedAt : Instant.now();
        }
    }

    public static final class ProcurementShipmentDto {
        public final String shipmentId;
        public final String trackingNumber;
        public final String tenantId;
        public final String branchId;
        public final String purchaseOrderId;
        public final String supplierId;
        public final String supplierName;
        public final String carrierType;
        public final String carrierName;
        public final String consignmentNumber;
        public final String vehicleRegistrationNumber;
        public final String driverName;
        public final String driverPhone;
        public String stage; // DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, RECEIVING_CONFIRMED, EXCEPTION, CANCELLED
        public final Instant dispatchedAt;
        public final Instant estimatedDeliveryAt;
        public Instant actualDeliveryAt;
        public final int totalPackagesDispatched;
        public int totalPackagesReceived;
        public int damagedPackages;
        public int missingPackages;
        public String discrepancyType; // NONE, SHORTAGE, DAMAGED, OVERAGE, TEMPERATURE_BREACH, REJECTED
        public String discrepancyNotes;
        public final boolean requiresColdChain;
        public BigDecimal transitTempCelsius;
        public boolean coldChainBreached;
        public String confirmedBy;
        public Instant confirmedAt;
        public final String notes;
        public final List<ShipmentEventDto> events;

        public ProcurementShipmentDto(
                String shipmentId, String trackingNumber, String tenantId, String branchId,
                String purchaseOrderId, String supplierId, String supplierName, String carrierType,
                String carrierName, String consignmentNumber, String vehicleRegistrationNumber,
                String driverName, String driverPhone, String stage, Instant dispatchedAt,
                Instant estimatedDeliveryAt, Instant actualDeliveryAt, int totalPackagesDispatched,
                int totalPackagesReceived, int damagedPackages, int missingPackages,
                String discrepancyType, String discrepancyNotes, boolean requiresColdChain,
                BigDecimal transitTempCelsius, boolean coldChainBreached, String confirmedBy,
                Instant confirmedAt, String notes, List<ShipmentEventDto> events) {
            this.shipmentId = shipmentId;
            this.trackingNumber = trackingNumber;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.purchaseOrderId = purchaseOrderId;
            this.supplierId = supplierId;
            this.supplierName = supplierName != null ? supplierName : "";
            this.carrierType = carrierType != null ? carrierType : "SUPPLIER_FLEET";
            this.carrierName = carrierName != null ? carrierName : "";
            this.consignmentNumber = consignmentNumber != null ? consignmentNumber : "";
            this.vehicleRegistrationNumber = vehicleRegistrationNumber != null ? vehicleRegistrationNumber : "";
            this.driverName = driverName != null ? driverName : "";
            this.driverPhone = driverPhone != null ? driverPhone : "";
            this.stage = stage != null ? stage : "DISPATCHED";
            this.dispatchedAt = dispatchedAt != null ? dispatchedAt : Instant.now();
            this.estimatedDeliveryAt = estimatedDeliveryAt;
            this.actualDeliveryAt = actualDeliveryAt;
            this.totalPackagesDispatched = Math.max(1, totalPackagesDispatched);
            this.totalPackagesReceived = totalPackagesReceived;
            this.damagedPackages = damagedPackages;
            this.missingPackages = missingPackages;
            this.discrepancyType = discrepancyType != null ? discrepancyType : "NONE";
            this.discrepancyNotes = discrepancyNotes != null ? discrepancyNotes : "";
            this.requiresColdChain = requiresColdChain;
            this.transitTempCelsius = transitTempCelsius;
            this.coldChainBreached = coldChainBreached;
            this.confirmedBy = confirmedBy;
            this.confirmedAt = confirmedAt;
            this.notes = notes != null ? notes : "";
            this.events = events != null ? events : new ArrayList<>();
        }
    }

    private void assertProcurementRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_PROCUREMENT_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot access logistics tracking.");
        }
    }

    private void assertReceivingRole() {
        if ("CASHIER".equalsIgnoreCase(userRole) || !ALLOWED_RECEIVING_ROLES.contains(userRole.toUpperCase())) {
            throw new SecurityException("ERR_STAFF_ROLE_DENIED: Staff role " + userRole + " cannot confirm receiving.");
        }
    }

    public ProcurementShipmentDto createShipment(
            String purchaseOrderId,
            String supplierId,
            String supplierName,
            String carrierType,
            String carrierName,
            String consignmentNumber,
            String vehicleRegistrationNumber,
            String driverName,
            String driverPhone,
            Instant estimatedDeliveryAt,
            int totalPackagesDispatched,
            boolean requiresColdChain,
            BigDecimal transitTempCelsius,
            String notes) {
        assertProcurementRole();

        if (carrierName == null || carrierName.trim().isEmpty()) {
            throw new IllegalArgumentException("ERR_CARRIER_REQUIRED: Carrier name is required.");
        }

        Instant now = Instant.now();
        if (estimatedDeliveryAt != null && estimatedDeliveryAt.isBefore(now.minusSeconds(60))) {
            throw new IllegalArgumentException("ERR_INVALID_ETA: Estimated delivery time cannot be in the past.");
        }

        boolean coldBreached = false;
        if (requiresColdChain && transitTempCelsius != null) {
            if (transitTempCelsius.compareTo(new BigDecimal("8.00")) > 0 || transitTempCelsius.compareTo(new BigDecimal("2.00")) < 0) {
                coldBreached = true;
            }
        }

        String shpId = "shp-" + UUID.randomUUID().toString().substring(0, 8);
        String trackingNum = "SHP-" + System.currentTimeMillis();

        List<ShipmentEventDto> events = new ArrayList<>();
        events.add(new ShipmentEventDto(
                null, "DISPATCHED", (supplierName != null ? supplierName : "Supplier Hub"),
                "Shipment dispatched with " + totalPackagesDispatched + " package(s). Carrier: " + carrierName,
                userId, now
        ));

        ProcurementShipmentDto shipment = new ProcurementShipmentDto(
                shpId, trackingNum, tenantId, branchId, purchaseOrderId, supplierId,
                supplierName, carrierType, carrierName, consignmentNumber, vehicleRegistrationNumber,
                driverName, driverPhone, "DISPATCHED", now, estimatedDeliveryAt, null,
                totalPackagesDispatched, 0, 0, 0, "NONE", "",
                requiresColdChain, transitTempCelsius, coldBreached, null, null, notes, events
        );

        shipmentStore.put(shpId, shipment);
        return shipment;
    }

    public ProcurementShipmentDto updateShipmentStage(
            String shipmentId,
            String newStage,
            String locationName,
            BigDecimal transitTempCelsius,
            String notes) {
        assertProcurementRole();

        ProcurementShipmentDto shipment = shipmentStore.get(shipmentId);
        if (shipment == null) {
            throw new NoSuchElementException("ERR_SHIPMENT_NOT_FOUND: Shipment " + shipmentId + " not found.");
        }

        if ("RECEIVING_CONFIRMED".equals(shipment.stage)) {
            throw new IllegalStateException("ERR_SHIPMENT_ALREADY_CONFIRMED: Cannot update stage on confirmed shipment.");
        }

        Instant now = Instant.now();
        shipment.stage = newStage;

        if (transitTempCelsius != null) {
            shipment.transitTempCelsius = transitTempCelsius;
            if (shipment.requiresColdChain) {
                if (transitTempCelsius.compareTo(new BigDecimal("8.00")) > 0 || transitTempCelsius.compareTo(new BigDecimal("2.00")) < 0) {
                    shipment.coldChainBreached = true;
                }
            }
        }

        if ("DELIVERED".equals(newStage) && shipment.actualDeliveryAt == null) {
            shipment.actualDeliveryAt = now;
        }

        shipment.events.add(new ShipmentEventDto(null, newStage, locationName, notes, userId, now));
        return shipment;
    }

    public ProcurementShipmentDto confirmBranchReceiving(
            String shipmentId,
            int totalPackagesReceived,
            int damagedPackages,
            int missingPackages,
            BigDecimal transitTempCelsius,
            String discrepancyNotes) {
        assertReceivingRole();

        ProcurementShipmentDto shipment = shipmentStore.get(shipmentId);
        if (shipment == null) {
            throw new NoSuchElementException("ERR_SHIPMENT_NOT_FOUND: Shipment " + shipmentId + " not found.");
        }

        if ("RECEIVING_CONFIRMED".equals(shipment.stage)) {
            throw new IllegalStateException("ERR_SHIPMENT_ALREADY_CONFIRMED: Shipment already confirmed.");
        }

        Instant now = Instant.now();
        int recv = Math.max(0, totalPackagesReceived);
        int dmg = Math.max(0, damagedPackages);
        int miss = Math.max(0, missingPackages);

        if ((recv + miss) < shipment.totalPackagesDispatched && miss == 0) {
            miss = shipment.totalPackagesDispatched - recv;
        }

        boolean coldBreached = shipment.coldChainBreached;
        if (transitTempCelsius != null) {
            shipment.transitTempCelsius = transitTempCelsius;
            if (shipment.requiresColdChain && (transitTempCelsius.compareTo(new BigDecimal("8.00")) > 0 || transitTempCelsius.compareTo(new BigDecimal("2.00")) < 0)) {
                coldBreached = true;
            }
        }
        shipment.coldChainBreached = coldBreached;

        String discType = "NONE";
        if (coldBreached) {
            discType = "TEMPERATURE_BREACH";
        } else if (dmg > 0) {
            discType = "DAMAGED";
        } else if (miss > 0) {
            discType = "SHORTAGE";
        } else if (recv > shipment.totalPackagesDispatched) {
            discType = "OVERAGE";
        }

        shipment.stage = "RECEIVING_CONFIRMED";
        shipment.totalPackagesReceived = recv;
        shipment.damagedPackages = dmg;
        shipment.missingPackages = miss;
        shipment.discrepancyType = discType;
        shipment.discrepancyNotes = discrepancyNotes != null ? discrepancyNotes : "";
        shipment.confirmedBy = userId;
        shipment.confirmedAt = now;
        if (shipment.actualDeliveryAt == null) {
            shipment.actualDeliveryAt = now;
        }

        shipment.events.add(new ShipmentEventDto(
                null, "RECEIVING_CONFIRMED", "Branch Receiving Dock",
                "Confirmed: " + recv + " received, " + dmg + " damaged, " + miss + " missing. Discrepancy: " + discType,
                userId, now
        ));

        return shipment;
    }

    public ProcurementShipmentDto getShipment(String shipmentId) {
        ProcurementShipmentDto shipment = shipmentStore.get(shipmentId);
        if (shipment == null) {
            throw new NoSuchElementException("ERR_SHIPMENT_NOT_FOUND: Shipment " + shipmentId + " not found.");
        }
        return shipment;
    }

    public List<ProcurementShipmentDto> listShipments(String stageFilter) {
        List<ProcurementShipmentDto> results = new ArrayList<>();
        for (ProcurementShipmentDto s : shipmentStore.values()) {
            if (stageFilter != null && !stageFilter.isEmpty() && !stageFilter.equalsIgnoreCase(s.stage)) {
                continue;
            }
            results.add(s);
        }
        return results;
    }
}
