# Bangladesh Pharmacy Desktop POS Client (JavaFX 21)

Native-feeling, restrained desktop Point of Sale (POS) client built with **Java 21 LTS, JavaFX 21, and Gradle**.
Ready to be pushed as its own independent GitHub repository (`pharmacy-desktop`).

---

## 1. Architecture & Capabilities
- **UI Design System:** macOS-inspired restrained neutral surfaces, 8pt spatial grid, SF Pro typography, strictly no emoji as UI icons.
- **Offline-First Resilience:** Encrypted local SQLite with durable outbox queue.
- **Synchronous Sales Integrity:** Counter sales validate price calculations with exact Decimal precision and atomic local receipting.
- **Hardware Integration:** ESC/POS thermal receipt printing, USB/Serial barcode scanner, and cash drawer kick.
- **Bilingual Localization:** English and Bengali UI labels with Lakh/Crore currency formatting.

---

## 2. Build & Test Commands
```bash
# Run unit and service tests
./gradlew test        # Linux/macOS
.\gradlew.bat test    # Windows

# Run application locally
./gradlew run
.\gradlew.bat run
```
