package me.rerere.rikkahub;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class BundledSkillInstaller {

    private static final String TAG = "BundledSkillInstaller";

    public static void copyDir(
            AssetManager assetManager,
            String path,
            File target
    ) throws Exception {

        String[] files = assetManager.list(path);

        if (files != null && files.length > 0) {

            if (!target.exists()) {
                target.mkdirs();
            }

            for (String file : files) {
                copyDir(
                        assetManager,
                        path + "/" + file,
                        new File(target, file)
                );
            }

            return;
        }


        // 文件存在则跳过
        if (target.exists() && target.isFile()) {
            return;
        }


        File parent = target.getParentFile();

        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }


        try (
                InputStream input =
                        assetManager.open(path);

                FileOutputStream output =
                        new FileOutputStream(target)
        ) {

            byte[] buffer = new byte[8192];

            int length;

            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }

            output.flush();
        }
    }



    public static void install(Context context) {

        try {

            SharedPreferences sp =
                    context.getSharedPreferences(
                            "bundled_skills",
                            Context.MODE_PRIVATE
                    );


            int version =
                    sp.getInt("installed_version", 0);



            if (version < 2) {


                File skillDir =
                        new File(
                                context.getFilesDir(),
                                "skills"
                        );


                copyDir(
                        context.getAssets(),
                        "skills",
                        skillDir
                );


                sp.edit()
                        .putInt(
                                "installed_version",
                                2
                        )
                        .apply();


                Log.i(
                        TAG,
                        "skills installed"
                );
            }


        } catch (Throwable e) {

            Log.e(
                    TAG,
                    "install failed",
                    e
            );
        }
    }
}
