"""
Garage door emulator API.
Run from this directory with: uvicorn main:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import json
import os
import random
import threading
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

APP_DIR = Path(__file__).resolve().parent
LOG_FILE = Path(os.getenv("GARAGE_LOG_FILE", APP_DIR / "door_events.jsonl"))
MAX_LOGS = 500

STATUS_TEXT = {
    1: "Закрыта",
    2: "Закрывается",
    3: "Открывается",
    4: "Открыта",
}

app = FastAPI(
    title="Garage Raspberry Emulator API",
    description="REST API for the GarageControl Android application",
    version="1.0.0",
)

# Добавляем CORS для Android-клиента
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

state_lock = threading.RLock()
state = {
    "status_code": 1,
    "lock_enabled": False,
    "motion_target": None,
    "motion_started": 0.0,
    "motion_duration": 4.0,
}

events: deque[dict] = deque(maxlen=MAX_LOGS)
gpio_value = 0


class DoorCommand(BaseModel):
    action: Literal["open", "close", "lock_on", "lock_off"]
    lock_enabled: bool = False


def timestamp() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def append_event(message: str) -> None:
    item = {"timestamp": timestamp(), "event": message}
    events.appendleft(item)
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        with LOG_FILE.open("a", encoding="utf-8") as fp:
            fp.write(json.dumps(item, ensure_ascii=False) + "\n")
    except OSError as exc:
        print(f"[WARN] Не удалось записать журнал: {exc}", flush=True)


def update_motion_locked() -> None:
    """Finish an opening/closing operation after the simulated travel time."""
    target = state["motion_target"]
    if target is None:
        return
    
    elapsed = time.monotonic() - state["motion_started"]
    if elapsed >= state["motion_duration"]:
        old = state["status_code"]
        state["status_code"] = target
        state["motion_target"] = None
        if old != target:
            append_event(f"Дверь: статус {target} — {STATUS_TEXT[target]}")


def status_payload() -> dict:
    with state_lock:
        update_motion_locked()
        code = state["status_code"]
        return {
            "status_code": code,
            "status_text": STATUS_TEXT[code],
            "lock_enabled": state["lock_enabled"],
        }


def start_motion(target_status: int) -> None:
    """Set transitional status and schedule completion through polling."""
    current = state["status_code"]
    if target_status == 4:
        if current == 4 and state["motion_target"] is None:
            append_event("Команда открытия: дверь уже открыта")
            return
        state["status_code"] = 3
        state["motion_target"] = 4
        label = "открывается"
    else:
        if current == 1 and state["motion_target"] is None:
            append_event("Команда закрытия: дверь уже закрыта")
            return
        state["status_code"] = 2
        state["motion_target"] = 1
        label = "закрывается"
        
    state["motion_started"] = time.monotonic()
    state["motion_duration"] = 4.0
    append_event(f"Дверь {label} (статус {state['status_code']})")


def apply_command(command: DoorCommand) -> None:
    """Обработка программных команд от Android (API)."""
    with state_lock:
        if command.action == "lock_on":
            state["lock_enabled"] = True
            append_event("Блокировка двери включена")
            return
        if command.action == "lock_off":
            state["lock_enabled"] = False
            append_event("Блокировка двери отключена")
            return

        # Синхронизируем состояние блокировки, если оно пришло от клиента
        state["lock_enabled"] = command.lock_enabled
        
        # ВАЖНО: Программное открытие/закрытие (через API) ВСЕГДА разрешено.
        # Защита блокирует только РУЧНЫЕ попытки (через GPIO).
        if command.action == "open":
            start_motion(4)
        elif command.action == "close":
            start_motion(1)


@app.get("/")
def root() -> dict:
    return {
        "service": "Garage Raspberry Emulator API",
        "docs": "/docs",
        "status_endpoint": "/api/door/status",
    }


@app.get("/api/door/status")
def get_door_status() -> dict:
    return status_payload()


@app.get("/api/door/logs")
def get_door_logs() -> list[dict]:
    with state_lock:
        update_motion_locked()
        return list(events)


@app.post("/api/door/command", status_code=204)
def post_door_command(command: DoorCommand):
    apply_command(command)
    return None


@app.post("/api/gpio/signal")
def simulate_gpio_signal(payload: dict):
    """Эмуляция РУЧНОГО сигнала с GPIO (физическая кнопка/взлом)."""
    value = payload.get("value")
    if value not in (0, 1, False, True):
        raise HTTPException(status_code=422, detail="value должен быть 0 или 1")
        
    global gpio_value
    gpio_value = int(value)
    action = payload.get("action", "open")
    append_event(f"GPIO: входной сигнал {gpio_value}")
    
    if gpio_value == 1:
        # Эмуляция ручной попытки открытия. Именно она блокируется защитой.
        with state_lock:
            if state["lock_enabled"]:
                append_event("Недопустимая попытка открытия двери вручную: блокировка включена")
                return {"accepted": False, "reason": "locked", "gpio_value": gpio_value}
                
        append_event("Допустимая попытка открытия двери вручную")
        if action == "close":
            start_motion(1)
        else:
            start_motion(4)
            
    return {"accepted": True, "gpio_value": gpio_value}


def keyboard_gpio_worker() -> None:
    """Read console commands in a daemon thread."""
    print("\nЭмуляция GPIO: введите o=ручное открытие, c=закрыть, r=случайный сигнал, q=выход.", flush=True)
    while True:
        try:
            command = input("GPIO > ").strip().lower()
        except (EOFError, OSError):
            return
            
        if command == "q":
            print("Поток GPIO остановлен. Сам API продолжает работать.", flush=True)
            return
        if command == "r":
            value = random.randint(0, 1)
            print(f"Сгенерирован GPIO={value}", flush=True)
            simulate_gpio_signal({"value": value, "action": "open"})
        elif command == "o":
            simulate_gpio_signal({"value": 1, "action": "open"})
        elif command == "c":
            with state_lock:
                append_event("Физическая команда GPIO: закрыть дверь")
                start_motion(1)
        elif command == "0":
            simulate_gpio_signal({"value": 0})
        elif command == "1":
            simulate_gpio_signal({"value": 1, "action": "open"})
        elif command:
            print("Команды: o, c, r, 0, 1, q", flush=True)


def load_existing_logs() -> None:
    if not LOG_FILE.exists():
        return
    try:
        lines = LOG_FILE.read_text(encoding="utf-8").splitlines()[-MAX_LOGS:]
        for line in lines:
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    except OSError as exc:
        print(f"[WARN] Не удалось загрузить журнал: {exc}", flush=True)


@app.on_event("startup")
async def startup_event() -> None:
    load_existing_logs()
    threading.Thread(target=keyboard_gpio_worker, daemon=True, name="gpio-keyboard").start()
    append_event("Сервер эмуляции гаражных ворот запущен")


@app.on_event("shutdown")
async def shutdown_event() -> None:
    append_event("Сервер эмуляции гаражных ворот остановлен")