# 465stats

Personal Android app for route 0465. Reads the XSales End of Day backups (copied, read-only), and shows pay, sales by store, inventory, the order guide, promotions and shortages.

## Install on the tablet
1. On the tablet, open https://github.com/jtsummerville/465stats/releases/latest
2. Download **465stats.apk** and open it. Allow "install unknown apps" for your browser if Android asks.
3. Open **465stats**, tap **Allow file access**, and turn on "All files access".

Each push to `main` builds a new APK (GitHub Actions) and replaces the `latest` release. Installing a newer build over the old one keeps your data.

## Backups
The app saves a backup of its data every day to `Documents/465stats backups` on the tablet (one zip per day, last 30 kept), locked with a backup password you set in Setup (AES-256 zip; opens in 7-Zip or WinRAR on a computer). Backups are off until a password is set. Setup has **Back up now** and **Restore from backup**. To get backups off the tablet automatically, point a sync app such as Autosync for Google Drive at that folder. The app itself has no internet access.
