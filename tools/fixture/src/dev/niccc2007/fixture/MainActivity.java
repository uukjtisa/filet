package dev.niccc2007.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Prints the fixture tag to logcat and finishes.
 *
 * No layout, no resources: the APK stays a few kilobytes, so decompiling it in a test costs
 * milliseconds instead of seconds.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i("FiletFixture", "tag=" + Fixture.tag());
        // A file as well as a log line. Some vendor builds filter logcat for third-party
        // apps, and then "did the patched code run?" has no answer at all - which is the one
        // question this fixture exists to settle.
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "ran.txt"))) {
            out.write(("tag=" + Fixture.tag()).getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e("FiletFixture", "could not record the run", e);
        }
        finish();
    }
}
