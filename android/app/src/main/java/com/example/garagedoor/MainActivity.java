package com.example.garagedoor;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private View garageDoor;
    private TextView tvLogs, tvStatusBadge, btnSettingsGear;
    private ScrollView scrollLogs;
    private Switch switchLock;
    private Button btnOpen, btnClose;

    private int devClickCount = 0;
    private static final String CHANNEL_ID = "garage_alarm_channel";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollingRunnable;

    // Флаг для предотвращения зацикливания при программном изменении Switch
    private boolean isUpdatingSwitch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        garageDoor = findViewById(R.id.garageDoor);
        tvLogs = findViewById(R.id.tvLogs);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        scrollLogs = findViewById(R.id.scrollLogs);
        btnSettingsGear = findViewById(R.id.btnSettingsGear);
        switchLock = findViewById(R.id.switchLock);
        btnOpen = findViewById(R.id.btnOpen);
        btnClose = findViewById(R.id.btnClose);

        createNotificationChannel();

        if (btnSettingsGear != null) {
            btnSettingsGear.setOnClickListener(v -> showSettingsMenu());
        }

        btnOpen.setOnClickListener(v -> sendCommand("open"));
        btnClose.setOnClickListener(v -> sendCommand("close"));

        switchLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Игнорируем событие, если мы сами только что изменили состояние из кода
            if (!isUpdatingSwitch) {
                sendCommand(isChecked ? "lock_on" : "lock_off");
            }
        });

        startServerPolling();

        // Запрос разрешения на уведомления для Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private String getBaseUrl() {
        SharedPreferences prefs = getSharedPreferences("GarageConfig", MODE_PRIVATE);
        return prefs.getString("server_url", "http://10.0.2.2:8000/");
    }

    private void showSettingsMenu() {
        CharSequence[] options = {"Инструменты разработчика", "О программе"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Настройки");
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                devClickCount++;
                if (devClickCount >= 3) {
                    devClickCount = 0;
                    showServerAddressDialog();
                } else {
                    Toast.makeText(this, "Тапните еще " + (3 - devClickCount) + " раза", Toast.LENGTH_SHORT).show();
                }
            } else if (which == 1) {
                showAboutDialog();
            }
        });
        builder.show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("О программе")
                .setMessage("Мобильный клиент управления гаражом (MVP).\n\n" +
                        "• Обработка статусов (1-4)\n" +
                        "• Защита от несанкционированного доступа\n" +
                        "• Синхронизация логов с сервером\n" +
                        "• REST API (FastAPI / Linux)")
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void showServerAddressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Настройка IP-адреса сервера");
        final EditText input = new EditText(this);
        input.setText(getBaseUrl());
        builder.setView(input);
        builder.setPositiveButton("Сохранить", (dialog, which) -> {
            String newUrl = input.getText().toString();
            getSharedPreferences("GarageConfig", MODE_PRIVATE)
                    .edit().putString("server_url", newUrl).apply();
            Toast.makeText(this, "Адрес сохранен", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void sendCommand(String action) {
        GarageApiService api = ApiClient.getService(getBaseUrl());
        DoorCommandRequest request = new DoorCommandRequest(action, switchLock.isChecked());
        api.sendCommand(request).enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) {
                fetchServerData();
            }

            @Override
            public void onFailure(Call<Void> call, Throwable t) {
                Toast.makeText(MainActivity.this, "Ошибка сети", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startServerPolling() {
        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                fetchServerData();
                handler.postDelayed(this, 3000);
            }
        };
        handler.post(pollingRunnable);
    }

    private void fetchServerData() {
        GarageApiService api = ApiClient.getService(getBaseUrl());

        api.getStatus().enqueue(new Callback<DoorStatusResponse>() {
            @Override
            public void onResponse(Call<DoorStatusResponse> call, Response<DoorStatusResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    DoorStatusResponse status = response.body();
                    updateUIByStatus(status.getStatusCode());

                    // Синхронизируем Switch без вызова лишнего сетевого запроса
                    if (switchLock.isChecked() != status.isLockEnabled()) {
                        isUpdatingSwitch = true;
                        switchLock.setChecked(status.isLockEnabled());
                        isUpdatingSwitch = false;
                    }
                }
            }

            @Override
            public void onFailure(Call<DoorStatusResponse> call, Throwable t) {
                // При опросе каждые 3 сек лучше молчать при временных обрывах сети, чтобы не спамить Toast'ами
            }
        });

        api.getLogs().enqueue(new Callback<List<LogEntry>>() {
            @Override
            public void onResponse(Call<List<LogEntry>> call, Response<List<LogEntry>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (LogEntry entry : response.body()) {
                        sb.append(entry.toString()).append("\n");

                        // Регистронезависимый поиск для гарантированного срабатывания тревоги
                        if (entry.getEvent() != null && entry.getEvent().toLowerCase().contains("недопустимая попытка")) {
                            triggerSecurityAlert(entry.getEvent());
                        }
                    }
                    tvLogs.setText(sb.toString());
                    scrollLogs.post(() -> scrollLogs.fullScroll(View.FOCUS_DOWN));
                }
            }

            @Override
            public void onFailure(Call<List<LogEntry>> call, Throwable t) {
                // Аналогично, молчим при временных обрывах сети
            }
        });
    }

    private void updateUIByStatus(int statusCode) {
        String statusTextDesc;
        float targetY;

        switch (statusCode) {
            case 1:
                statusTextDesc = "1 — Закрыта";
                targetY = 0f;
                break;
            case 2:
                statusTextDesc = "2 — Закрывается...";
                targetY = 0f;
                break;
            case 3:
                statusTextDesc = "3 — Открывается...";
                targetY = -garageDoor.getHeight();
                break;
            case 4:
                statusTextDesc = "4 — Открыта";
                targetY = -garageDoor.getHeight();
                break;
            default:
                statusTextDesc = "Неизвестный статус";
                targetY = 0f;
        }

        if (tvStatusBadge != null) {
            tvStatusBadge.setText("Статус: " + statusTextDesc);
        }

        ObjectAnimator.ofFloat(garageDoor, "translationY", targetY).setDuration(1000).start();
    }

    private void triggerSecurityAlert(String alertDetails) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("⚠️ ТРЕВОГА: Попытка взлома!")
                    .setContentText(alertDetails)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true);
            manager.notify(2, builder.build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Сигнализация Гаража", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pollingRunnable != null) handler.removeCallbacks(pollingRunnable);
    }
}