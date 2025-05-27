package de.tu_darmstadt.seemoo.nfcgate.gui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import de.tu_darmstadt.seemoo.nfcgate.R;

public class LoginActivity extends AppCompatActivity {

    EditText codeInput;
    Button loginButton;
    SharedPreferences prefs;
    final String PREF_CODE_KEY = "access_code";
    final String API_BASE_URL = "http://178.128.136.124:8000/api/";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_login);

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        codeInput = findViewById(R.id.code_input);
        loginButton = findViewById(R.id.login_button);

        String savedCode = prefs.getString(PREF_CODE_KEY, null);
        if (savedCode != null){
            verifyCode(savedCode, true);
        }

        loginButton.setOnClickListener(v -> {
            String code = codeInput.getText().toString();
            verifyCode(code, false);
        });
    }

    private void verifyCode(String code, boolean silentLogin) {
        new Thread(() -> {
            try {
                URL url = new URL(API_BASE_URL + "accesscode/verify?code=" + code);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder responce = new StringBuilder();
                    String inputLine;

                    while ((inputLine = in.readLine()) != null){
                        responce.append(inputLine);
                    }
                    in.close();

                    JSONObject jsonResponce = new JSONObject(responce.toString());
                    boolean isValid = jsonResponce.getBoolean("valid");

                    if (isValid){
                        prefs.edit().putString(PREF_CODE_KEY, code).apply();
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Login successful", Toast.LENGTH_SHORT).show();
                            Intent intent = new Intent(this, MainActivity.class);
                            startActivity(intent);
                            finish();
                        });
                    }
                    else {
                        if (!silentLogin) {
                            runOnUiThread(() ->
                                    Toast.makeText(this, "Invalid or expired code", Toast.LENGTH_SHORT).show()
                            );
                        }
                    }
                }
                else {
                    if (!silentLogin){
                        runOnUiThread(() -> {
                            Toast.makeText(this, "Invalid or expired code", Toast.LENGTH_SHORT).show();
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}