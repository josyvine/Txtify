package com.hfm.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class CrashReportActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Use a generic theme to avoid any potential theme-related crashes.
        setTheme(android.R.style.Theme_DeviceDefault);
        setContentView(R.layout.activity_crash_report);

        final TextView errorTextView = findViewById(R.id.crash_report_text);
        final Button closeButton = findViewById(R.id.crash_report_close_button);

        // Get the crash report string from the Intent that started this activity.
        String crashReport = getIntent().getStringExtra(MyExceptionHandler.EXTRA_CRASH_REPORT);
        if (crashReport != null) {
            errorTextView.setText(crashReport);
        } else {
            errorTextView.setText("No crash report data available.");
        }

        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Exit the application completely.
                finish();
                System.exit(0);
            }
        });
    }
}