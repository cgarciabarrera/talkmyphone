package com.googlecode.talkmyphone;

import com.googlecode.talkmyphone.R;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;


public class MainScreen extends Activity {

    //public final static TextView txtEstado = (TextView) findViewById(R.id.mensaje);

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Button prefBtn = (Button) findViewById(R.id.Preferences);
        prefBtn.setOnClickListener(new OnClickListener() {

                public void onClick(View v) {
                    Intent settingsActivity = new Intent(getBaseContext(), Preferences.class);
                    startActivity(settingsActivity);
                }
        });

        Button startStopButton = (Button) findViewById(R.id.StartStop);
        startStopButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    Intent intent = new Intent(".TalkMyPhone.ACTION");
                    if (!XmppService.isRunning(getApplicationContext())) {
                        startService(intent);
                    }
                    else {
                        stopService(intent);
                    }
                }
        });

    }
    public static void Actualizatexto(String texto)
    {
    	//Toast.makeText(getApplicationContext(), texto, SH)
    	//txtEstado.setText(texto);
    }

}
