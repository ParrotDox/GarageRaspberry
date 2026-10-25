# Garage Raspberry — серверная часть

Сервер написан на FastAPI и работает на Linux/VM или Windows без физической Raspberry Pi. GPIO эмулируется вводом с клавиатуры и генератором случайных чисел.

## 1. Установка и запуск

Из корневой папки `GarageRaspberry`:

### Linux / macOS
```bash
cd server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

### Windows PowerShell
```powershell
cd server
py -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000
```

API-документация: `http://127.0.0.1:8000/docs`.

## 2. Подключение Android

- Android-эмулятор: обычно `http://10.0.2.2:8000/`
- Физический телефон: `http://IP_КОМПЬЮТЕРА_С_СЕРВЕРОМ:8000/`, оба устройства должны быть в одной сети.
- Если сервер в VM, сетевой режим VM и firewall должны разрешать входящие соединения на TCP-порт 8000.

Укажите адрес в настройках приложения. Для локального HTTP в Android-манифесте клиента должно быть разрешено cleartext traffic (`android:usesCleartextTraffic="true"`) либо настройка Network Security Config.

## 3. Команды эмуляции GPIO в терминале

Когда сервер запущен в интерактивном терминале, доступны:

- `o` — ручная попытка открыть ворота; будет разрешена, если блокировка выключена.
- `c` — физическая команда закрыть ворота.
- `r` — сгенерировать случайный сигнал GPIO 0 или 1.
- `0` / `1` — подать низкий/высокий сигнал.
- `q` — завершить только поток чтения клавиатуры; API продолжит работать.

Можно эмулировать сигнал и через API:
```bash
curl -X POST http://127.0.0.1:8000/api/gpio/signal \
  -H "Content-Type: application/json" \
  -d '{"value":1,"action":"open"}'
```

## 4. Контракт Android API

- `GET /api/door/status` возвращает `status_code`, `status_text`, `lock_enabled`.
- `GET /api/door/logs` возвращает массив объектов `timestamp` + `event` (новые события первыми).
- `POST /api/door/command` принимает JSON с `action` (`open`, `close`, `lock_on`, `lock_off`) и `lock_enabled`.

Статусы: `1` закрыта, `2` закрывается, `3` открывается, `4` открыта. Движение длится 4 секунды, после чего сервер переходит в конечный статус.

Журнал хранится в `server/door_events.jsonl`; новые события добавляются с локальной временной меткой и часовым поясом.