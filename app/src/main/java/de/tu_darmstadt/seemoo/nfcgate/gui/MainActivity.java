package de.tu_darmstadt.seemoo.nfcgate.gui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import androidx.annotation.Nullable;
import com.google.android.material.navigation.NavigationView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.os.Handler;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import de.tu_darmstadt.seemoo.nfcgate.R;
import de.tu_darmstadt.seemoo.nfcgate.db.SessionLog;
import de.tu_darmstadt.seemoo.nfcgate.db.pcapng.ISO14443Stream;
import de.tu_darmstadt.seemoo.nfcgate.db.worker.LogInserter;
import de.tu_darmstadt.seemoo.nfcgate.gui.fragment.StatusFragment;
import de.tu_darmstadt.seemoo.nfcgate.gui.fragment.RelayFragment;
import de.tu_darmstadt.seemoo.nfcgate.gui.fragment.SettingsFragment;
import de.tu_darmstadt.seemoo.nfcgate.network.UserTrustManager;
import de.tu_darmstadt.seemoo.nfcgate.nfc.NfcManager;
import de.tu_darmstadt.seemoo.nfcgate.util.NfcComm;

public class MainActivity extends AppCompatActivity {
    // UI
    DrawerLayout mDrawerLayout;
    NavigationView mNavbar;
    Toolbar mToolbar;
    ActionBarDrawerToggle mToggle;

    // NFC
    NfcManager mNfc;

    // API
    SharedPreferences prefs;
    final String PREF_CODE_KEY = "access_code";
    final String API_BASE_URL = "http://178.128.136.124:8000/api/";

    Handler handler = new Handler();
    Runnable codeCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkCodeValidity();
            handler.postDelayed(this, 5*60*1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);

        // toolbar setup
        mToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);

        // drawer setup
        mDrawerLayout = findViewById(R.id.main_drawer_layout);

        // drawer toggle in toolbar
        mToggle = new ActionBarDrawerToggle(this, mDrawerLayout, mToolbar, R.string.empty, R.string.empty);
        mToggle.setToolbarNavigationClickListener(v -> {
            // when drawer icon is NOT visible (due to fragment on backstack), issue back action
            onBackPressed();
        });
        mDrawerLayout.addDrawerListener(mToggle);

        // display "up-arrow" when non-empty backstack, display navigation drawer otherwise
        final FragmentManager fragmentManager = getSupportFragmentManager();
        final ActionBar actionBar = getSupportActionBar();

        fragmentManager.addOnBackStackChangedListener(() -> {
            if (fragmentManager.getBackStackEntryCount() > 0) {
                // https://stackoverflow.com/a/29594947
                actionBar.setDisplayHomeAsUpEnabled(false);
                mToggle.setDrawerIndicatorEnabled(false);
                actionBar.setDisplayHomeAsUpEnabled(true);
            } else {
                actionBar.setDisplayHomeAsUpEnabled(false);
                mToggle.setDrawerIndicatorEnabled(true);
                mToggle.syncState();
            }
        });

        // navbar setup actions
        mNavbar = findViewById(R.id.main_navigation);
        mNavbar.setNavigationItemSelectedListener(item -> {
            onNavbarAction(item);
            return true;
        });

        // NFC setup
        mNfc = new NfcManager(this);
        if (!mNfc.hasNfc() || !mNfc.isEnabled())
            showWarning(getString(R.string.error_NFCCAP));

        // TLS setup
        UserTrustManager.init(this);

        // Api check
        handler.post(codeCheckRunnable);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // pass initial intent to current mode in case it carries a tag
        if (getIntent() != null)
            onNewIntent(getIntent());
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        mToggle.syncState();
        super.onPostCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // tech discovered is triggered by XML, tag discovered by foreground dispatch
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction()) ||
                NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction()))
            mNfc.onTagDiscovered(intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));
        else if (Intent.ACTION_SEND.equals(intent.getAction()))
            importPcap(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        else if (Intent.ACTION_VIEW.equals(intent.getAction()))
            importPcap(intent.getData());
        else if ("de.tu_darmstadt.seemoo.nfcgate.daemoncall".equals(intent.getAction()))
            mNfc.getDaemon().onResponse(intent);
        else
            super.onNewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mNfc.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mNfc.onPause();
    }

    /**
     * Returns a Fragment for every navbar action
     */
    private Fragment getFragmentByAction(int id) {
        if (R.id.nav_relay == id) {
            return new RelayFragment();
        } else if (R.id.nav_settings == id) {
            return new SettingsFragment();
        } else if (R.id.nav_status == id) {
            return new StatusFragment();
        }

        throw new IllegalArgumentException("Position out of range");
    }

    /**
     * Handles all navbar actions by creating a new fragment
     */
    private void onNavbarAction(MenuItem item) {
        // every fragment must implement BaseFragment
        Fragment fragment = getFragmentByAction(item.getItemId());

        // remove all currently opened on-top fragments (e.g. log entry)
        getSupportFragmentManager().popBackStack();
        // no fancy animation for now
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.main_content, fragment)
                .commit();

        // for the looks
        getSupportActionBar().setTitle(item.getTitle());
        // reset the subtitle because a fragment might have changed it
        getSupportActionBar().setSubtitle(null);
        // hide status bar
        findViewById(R.id.banner).setVisibility(View.GONE);

        // avoid carrying over actions from previous fragment
        supportInvalidateOptionsMenu();

        mDrawerLayout.closeDrawers();
    }

    private void importPcap(Uri uri) {
        try {
            LogInserter inserter = new LogInserter(this, SessionLog.SessionType.RELAY, null);

            for (NfcComm e : new ISO14443Stream().readAll(getContentResolver().openInputStream(uri)))
                inserter.log(e);
            Toast.makeText(this, getString(R.string.pcap_success), Toast.LENGTH_SHORT).show();
        }
        catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.pcap_error), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        // reset the subtitle because a fragment might have changed it
        getSupportActionBar().setSubtitle(null);

        super.onBackPressed();
    }

    /**
     * Displays a warning dialog with the specified message
     */
    public void showWarning(String warning) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.status_warning))
                .setMessage(warning)
                .setNegativeButton(R.string.button_ok, null)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .show();
    }

    public NfcManager getNfc() {
        return mNfc;
    }

    private boolean isExpired(String expiresAtStr){
        try{
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault());
            Date expiresAt = sdf.parse(expiresAtStr);
            return new Date().after(expiresAt);
        }
        catch (Exception e){
            return true;
        }
    }

    private void checkCodeValidity(){
        String code = prefs.getString(PREF_CODE_KEY, null);
        if (code == null) {
            Log.d("MainActivity", "No access code found.");
            return;
        }

        new Thread(() -> {
           try{
               URL url = new URL(API_BASE_URL + "accesscode/verify?code=" + code);
               HttpURLConnection conn = (HttpURLConnection) url.openConnection();
               conn.setRequestMethod("GET");
               conn.setRequestProperty("Accept", "application/json");

               var resCode = conn.getResponseCode();

               if (resCode == 200) {
                   BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                   StringBuilder response = new StringBuilder();
                   String inputLine;

                   while ((inputLine = in.readLine()) != null){
                       response.append(inputLine);
                   }
                   in.close();

                   JSONObject jsonResponse = new JSONObject(response.toString());
                   boolean isValid = jsonResponse.getBoolean("valid");

                   if (!isValid){
                       runOnUiThread(() -> {
                           Toast.makeText(this, "Code expired", Toast.LENGTH_SHORT).show();
                           prefs.edit().remove(PREF_CODE_KEY).apply();
                           startActivity(new Intent(this, LoginActivity.class));
                           finish();
                       });
                   }
                   else {
                       runOnUiThread(() -> Log.d("MainActivity", "Access code is still valid."));
                   }
               }
               else {
                   Log.e("MainActivity", "Server error: " + resCode);
                   runOnUiThread(() -> Toast.makeText(this, "Verification failed", Toast.LENGTH_SHORT).show());
               }
           }
           catch (Exception ex){
               Log.e("MainActivity", "Code verification exception", ex);
               runOnUiThread(() -> Toast.makeText(this, "Check failed", Toast.LENGTH_SHORT).show());
           }
        }).start();
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        handler.removeCallbacks(codeCheckRunnable);
    }
}
