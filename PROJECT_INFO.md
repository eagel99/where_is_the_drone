# SkyPoint - Drone & Bird Tracking System

SkyPoint is an Android application designed for real-time tracking and estimation of distances to airborne objects such as drones (UAVs) and birds. It uses the device's camera, sensors, and GPS to provide precise coordinates and elevation data.

## Features

-   **Real-time Camera Overlay**: Visual reticle for targeting objects.
-   **Distance Estimation**: Calculates distance to targets based on known physical sizes and camera FOV.
-   **Precise Positioning**: Uses GPS, compass (azimuth), and pitch sensors to determine the target's latitude, longitude, and altitude.
-   **Barometric Support**: Utilizes the device's barometer (if available) for more accurate altitude readings.
-   **Target History**: Saves captured target data securely for later review.
-   **Secure Data Sharing**: Export target reports via WhatsApp or Email.
-   **Enhanced Security**: 
    -   Encrypted data storage at rest using `EncryptedSharedPreferences`.
    -   Disabled cloud and local backups for sensitive location data.
    -   Code obfuscation enabled for release builds.

## Technical Details

-   **Language**: Kotlin
-   **UI Framework**: Jetpack Compose
-   **Camera API**: CameraX
-   **Location API**: Google Play Services Location
-   **Sensor Integration**: Rotation Vector (Compass/Pitch) and Barometer.
-   **Persistence**: GSON for JSON serialization and `EncryptedSharedPreferences` for secure storage.

## Target Types

The system includes pre-defined sizes for common objects to aid in distance estimation:
-   **UAVs**: Shahed-136, Mini UAV, Pro UAV, Large UAV.
-   **Birds**: City Bird, Raptor, Small Bird.

## Security & Privacy

SkyPoint is built with security in mind:
-   **No Backups**: `android:allowBackup="false"` prevents data extraction via backup services.
-   **AES Encryption**: All captured coordinates and timestamps are encrypted using AES-256 before being saved to internal storage.
-   **R8 Minification**: Protects against reverse engineering.

---
*Developed for professional observation and tracking.*
