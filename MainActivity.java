package com.roohu.airlink;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LanSharePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
