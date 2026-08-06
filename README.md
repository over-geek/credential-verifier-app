# Credential Verifier App

A native Android application built as a proof-of-concept for verifying academic credentials. This app allows verifiers to seamlessly authenticate physical academic certificates using either printed QR codes or embedded secure NFC chips.

## Features

- **QR Code Verification**: Uses the device camera and ML Kit to scan credential QR codes, fetching real-time verification data from the backend API.
- **Offline NFC Verification**: Reads encrypted and digitally signed credential payloads directly from embedded MIFARE Classic (1K/4K) NFC chips.
- **Cryptographic Anti-Cloning**: Verifies Ed25519 digital signatures bound to the physical factory UID of the NFC chip, preventing ciphertext from being copied to blank cards.
- **Dynamic Photo Fetching**: Instantly displays offline textual data from the chip while asynchronously fetching the student's photo over the network if available.
- **Offline Resilience**: Fully supports verifying text-based credential records even in airplane mode or areas with no internet connectivity.

## Tech Stack

- **Language**: Kotlin
- **Platform**: Native Android (Min SDK 33, Target SDK 37)
- **NFC**: Android `android.nfc.tech.MifareClassic` foreground dispatch
- **Camera/Vision**: CameraX, Google ML Kit (Barcode Scanning)
- **Networking**: Retrofit2, Gson
- **Cryptography**: Ed25519 signature verification, AES-GCM decryption
