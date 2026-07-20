# KaRadioController1
KaRadioController is a robust Android remote control application specifically designed for the KaRadio32 (ESP32 web radio) ecosystem. Featuring a futuristic Cyberpunk UI, it transforms your smartphone into a high-tech terminal for managing every aspect of your hardware radio.
🚀 Detailed Functionality & Capabilities
1. 🎧 Multi-dimensional Player Control
The core of the application provides unparalleled control over your audio experience:
•
Instant Response: High-speed command delivery via HTTP and real-time status updates through WebSockets.
•
Visual Feedback: A dynamic dashboard showing:
◦
Connection Indicator: Live status dot (Green for Online, Red for Offline).
◦
Rich Metadata: Automatic parsing and display of Station Name, Track Title, and Artist.
◦
Technical Specs: Real-time Bitrate display (kbps).
•
Pro Audio Tools:
◦
Hardware Volume: Full range slider (0–254) synchronized with the device.
◦
VS1053 Equalizer: Deep integration for Bass/Treble levels and center frequency adjustment.
◦
Spacialization: Toggle 3D sound effects (Off, Minimal, Normal, Max).
2. 📜 Optimized Playlist Management
•
Full Spectrum Sync: Manages the complete set of 255 station slots (0–254).
•
Intelligent Pre-loading: To ensure a smooth UI experience, the app pre-fills the list with [Empty Station] placeholders and updates them in the background.
•
Advanced Station Editor:
◦
Automatic URL parsing (Extracts Host, Port, and Path from full links).
◦
Volume offset (OVOL) adjustment per station.
◦
Easy station erasing and renaming.
3. ⚙️ System & Network Configuration
Access deep hardware settings without using the browser:
•
Smart Wi-Fi Setup: Configure primary and secondary Access Points (SSID/Password).
•
WebSocket Scanner: Trigger a live Wi-Fi scan on the ESP32 and see results instantly in the app.
•
Hardware Profile: Switch between output modes: I2S, MERUS, DAC, PDM, VS1053, and SPDIF.
•
Hostname Control: Customize the device name for easier network identification.
4. 🔊 Audio Monitoring (Unique Feature)
The Audio Monitoring mode allows you to listen to the radio stream on your phone simultaneously.
•
When enabled, the app mirrors the current station being played by KaRadio32.
•
Perfect for testing stations or listening when away from the main speakers.
5. 🆙 Intelligent Firmware Maintenance
•
Local Versioning: Automatically detects and displays the currently installed firmware.
•
Cloud Check: Directly queries the developer's server (karawin.fr) using a specialized HTML parser to find the latest stable Release/Revision.
•
One-Click OTA: Start the Over-The-Air update process directly from your phone.
🎨 UI/UX Design
The app features a Cyberpunk 2077-inspired theme:
•
Neon Aesthetic: High-contrast Cyan and Magenta accents on a deep obsidian background.
•
Monospaced Typography: Terminal-style fonts for an authentic technical feel.
•
Adaptive Layout: Optimized with Material Design constraints (488dp max width) to look perfect on both compact smartphones and large tablets.
🛠 Technical Stack
•
Kotlin: 100% modern, safe, and concise code.
•
OkHttp 5: Latest generation of networking for reliable device communication.
•
WebSockets: Persistent duplex connection for sub-second status latency.
•
Android KTX: Modern visibility and lifecycle management.
KaRadioController (RU)
KaRadioController — это мощное Android-приложение для дистанционного управления KaRadio32 (интернет-радио на ESP32). Благодаря интерфейсу в стиле Cyberpunk, ваш смартфон превращается в высокотехнологичный терминал управления звуком.
🚀 Подробный функционал и возможности
1. 🎧 Управление плеером
Центральный пульт управления вашим аудио-опытом:
•
Мгновенный отклик: Отправка команд через HTTP и получение статуса через WebSockets.
•
Визуальный контроль:
◦
Индикатор сети: Живая точка состояния (Зеленая — Онлайн, Красная — Офлайн).
◦
Метаданные: Автоматическое отображение Названия станции, Трека и Исполнителя.
◦
Техданные: Отображение битрейта (kbps) в реальном времени.
•
Профессиональный звук:
◦
Громкость: Ползунок (0–254), синхронизированный с устройством.
◦
Эквалайзер VS1053: Тонкая настройка уровней и частот Bass/Treble.
◦
Spacialization: Настройка эффекта объема (Off, Minimal, Normal, Max).
2. 📜 Управление плейлистом
•
Полная синхронизация: Работа со всеми 255 слотами (0–254).
•
Умная загрузка: Список мгновенно заполняется заглушками [Empty Station] и обновляется по мере опроса устройства.
•
Продвинутый редактор:
◦
Парсинг полных ссылок (авто-извлечение хоста, порта и пути).
◦
Настройка индивидуального смещения громкости (OVOL) для каждой станции.
3. ⚙️ Конфигурация системы
Настройка «железа» без обращения к веб-интерфейсу:
•
Настройка Wi-Fi: Конфигурация двух точек доступа (SSID/Пароль).
•
Сканер сетей: Запуск поиска Wi-Fi сетей на ESP32 с выводом результатов в приложении.
•
Профили оборудования: Смена типа вывода: I2S, MERUS, DAC, PDM, VS1053, SPDIF.
4. 🔊 Audio Monitoring (Уникальная функция)
Режим мониторинга позволяет слушать радио на вашем телефоне одновременно с KaRadio32.
•
Приложение автоматически подхватывает тот же поток, что играет устройство.
•
Идеально для проверки станций или прослушивания в наушниках через телефон.
5. 🆙 Обновление прошивки
•
Авто-определение: Показ текущей версии прошивки сразу при открытии.
•
Облачная проверка: Запрос к серверу разработчика (karawin.fr) для поиска новой версии.
•
OTA Update: Запуск беспроводного обновления одной кнопкой.
🎨 Дизайн и интерфейс
Приложение выполнено в стиле Cyberpunk 2077:
•
Неоновая эстетика: Контрастные цвета (Cyan/Magenta) на глубоком темном фоне.
•
Техно-шрифты: Моноширинные шрифты для создания атмосферы хакерского терминала.
•
Адаптивность: Интерфейс ограничен шириной 488dp, что обеспечивает отличный вид как на телефонах, так и на планшетах.
