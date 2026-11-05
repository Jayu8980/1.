package com.hft.piano2midi;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Connects to every USB / Bluetooth MIDI keyboard Android knows about (hot-plug aware)
 * and reports note-on / note-off messages on the main thread.
 */
final class MidiInput {

    interface Listener {
        void onMidiNote(int pitch, int velocity, boolean on);

        void onMidiDevices(String names);
    }

    private final MidiManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MidiDevice> devices = new ArrayList<MidiDevice>();
    private final List<MidiOutputPort> ports = new ArrayList<MidiOutputPort>();
    private final List<String> names = new ArrayList<String>();

    private final MidiManager.DeviceCallback callback = new MidiManager.DeviceCallback() {
        @Override
        public void onDeviceAdded(MidiDeviceInfo info) {
            open(info);
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo info) {
            reopenAll();
        }
    };

    private MidiInput(MidiManager m, Listener l) {
        manager = m;
        listener = l;
    }

    /** Null when the device has no MIDI support. */
    static MidiInput create(Context ctx, Listener l) {
        if (!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) return null;
        MidiManager m = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
        if (m == null) return null;
        MidiInput in = new MidiInput(m, l);
        m.registerDeviceCallback(in.callback, in.main);
        for (MidiDeviceInfo info : m.getDevices()) in.open(info);
        in.publish();
        return in;
    }

    private void open(final MidiDeviceInfo info) {
        if (info.getOutputPortCount() == 0) return; // we read from the keyboard's output ports
        manager.openDevice(info, new MidiManager.OnDeviceOpenedListener() {
            @Override
            public void onDeviceOpened(MidiDevice device) {
                if (device == null) return;
                devices.add(device);
                for (int i = 0; i < info.getOutputPortCount(); i++) {
                    MidiOutputPort port = device.openOutputPort(i);
                    if (port == null) continue;
                    port.connect(new Parser());
                    ports.add(port);
                }
                Bundle p = info.getProperties();
                String name = p.getString(MidiDeviceInfo.PROPERTY_NAME);
                if (name == null) name = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
                names.add(name != null ? name : "MIDI device");
                publish();
            }
        }, main);
    }

    private void publish() {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n);
        }
        listener.onMidiDevices(sb.toString());
    }

    private void reopenAll() {
        closePorts();
        for (MidiDeviceInfo info : manager.getDevices()) open(info);
        publish();
    }

    private void closePorts() {
        for (MidiOutputPort p : ports) {
            try {
                p.close();
            } catch (IOException ignored) { }
        }
        for (MidiDevice d : devices) {
            try {
                d.close();
            } catch (IOException ignored) { }
        }
        ports.clear();
        devices.clear();
        names.clear();
    }

    void close() {
        manager.unregisterDeviceCallback(callback);
        closePorts();
    }

    /** Minimal MIDI byte-stream parser (running status, note on/off only). */
    private final class Parser extends MidiReceiver {
        private int status, d1 = -1;

        @Override
        public void onSend(byte[] msg, int offset, int count, long timestamp) {
            for (int i = offset; i < offset + count; i++) {
                int b = msg[i] & 0xFF;
                if (b >= 0xF8) continue;          // real-time messages
                if (b >= 0x80) {
                    status = b;
                    d1 = -1;
                    continue;
                }
                int type = status & 0xF0;
                if (type != 0x80 && type != 0x90) continue;
                if (d1 < 0) {
                    d1 = b;
                } else {
                    final int pitch = d1, vel = b;
                    final boolean on = type == 0x90 && vel > 0;
                    d1 = -1;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onMidiNote(pitch, vel, on);
                        }
                    });
                }
            }
        }
    }
}
