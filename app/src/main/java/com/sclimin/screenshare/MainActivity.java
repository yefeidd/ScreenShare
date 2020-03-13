package com.sclimin.screenshare;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;

public class MainActivity extends AppCompatActivity {

    private Button mStartButton;
    private EditText mEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        App.listener = this::uiUpdate;

        mStartButton = findViewById(R.id.start);
        mEditText = findViewById(R.id.ip);
        mEditText.setText(App.ip);
        mEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }

            @Override
            public void afterTextChanged(Editable s) {
                App.ip = s.toString();
            }
        });

        mStartButton.setOnClickListener(v -> {
            if (App.ip == null || App.ip.isEmpty()) {
                return;
            }

            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm != null)
            {
                startActivityForResult(mpm.createScreenCaptureIntent(), 10);
            }
        });

        uiUpdate();
    }

    private void uiUpdate() {
        mEditText.setEnabled(App.service == null);
        mStartButton.setEnabled(App.service == null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 10)  {
            Intent service = new Intent(this, ScreenShareService.class);
            service.putExtra("result-code", resultCode);
            service.putExtra("data", data);
            startService(service);
        }
    }
}
