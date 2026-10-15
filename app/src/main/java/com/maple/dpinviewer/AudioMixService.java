package com.maple.dpinviewer;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

public final class AudioMixService extends Service {
    private AudioMixFix audio;
    private int milinkUid = -1;
    private int recorderUid = -1;
    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != 1 && code != 2 && code != 3) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(AudioMixFix.INTERFACE);
            if (code == 3) {
                if (Binder.getCallingUid() != recorderUid) throw new SecurityException("Recorder only");
                reply.writeNoException();
                reply.writeInt(audio.ready() ? 1 : 0);
                return true;
            }
            if (Binder.getCallingUid() != milinkUid) throw new SecurityException("MiLink only");
            long identity = Binder.clearCallingIdentity();
            try {
                boolean success;
                if (code == 1) audio.start();
                success = code == 1 ? audio.ready() : audio.stop();
                reply.writeNoException();
                reply.writeInt(success ? 1 : 0);
                return true;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = new AudioMixFix(this);
        try { milinkUid = getPackageManager().getApplicationInfo(Protocol.TARGET, 0).uid; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        try { recorderUid = getPackageManager().getApplicationInfo(RecorderEchoHook.TARGET, 0).uid; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() { audio.stop(); super.onDestroy(); }
}
