package android.location;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

public interface IGpsStatusListener extends IInterface {
    void onGpsStarted();
    void onGpsStopped();
    void onFirstFix(int ttff);
    void onSvStatusChanged(int svCount, int[] prns, float[] snrs,
            float[] elevations, float[] azimuths,
            int ephemerisMask, int almanacMask, int usedInFixMask, int glonassUsedInFixMask);
    void onNmeaReceived(long timestamp, String nmea);

    abstract class Stub extends Binder implements IGpsStatusListener {
        public Stub() {
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
