package jonathanfinerty.once;

import android.content.Context;
import android.content.SharedPreferences;

public class Once {

    public static final String THIS_APP_INSTALL = "__once_app_install__";

    private static SharedPreferences prefs;

    public static void initialise(Context context) {
        prefs = context.getSharedPreferences("jonathanfinerty.once", Context.MODE_PRIVATE);
    }

    public static boolean beenDone(String... tags) {
        if (prefs == null) return false;
        for (String tag : tags) {
            if (!prefs.getBoolean(tag, false)) {
                return false;
            }
        }
        return true;
    }

    public static void markDone(String tag) {
        if (prefs == null) return;
        prefs.edit().putBoolean(tag, true).apply();
    }
}
