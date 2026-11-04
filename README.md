# Meine-Finanzen

Native Android app (Java) for a personal CHF income/expense ledger.

## Build
GitHub Actions builds a debug APK on every push to `main`. Open **Actions → Android APK** and download the `meine-finanzen-debug-apk` artifact after a successful run.

## Current implementation
- Account selection and account management
- CHF income and expense entries with purpose, amount and date
- Month/year filters, including whole year
- PDF export and Excel-compatible CSV export through Android's save-file dialog
- Local JSON backup export/import
- Optional app PIN gate
- Android system status and navigation bars remain visible

Cloud backup to Google Drive/OneDrive and biometric unlock require additional account/provider setup and are not silently enabled.
