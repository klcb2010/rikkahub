package me.rerere.rikkahub;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class BundledSkillInstaller {

    public static void copyDir(
            AssetManager assetManager,
            String path,
            File file
    ) throws Exception {

        String[] list = assetManager.list(path);

        if (list != null && list.length != 0) {

            if (!file.exists()) {
                file.mkdirs();
            }

            for (String name : list) {

                copyDir(
                        assetManager,
                        path + "/" + name,
                        new File(file, name)
                );
            }

            return;
        }


        if (file.exists()) {
            return;
        }


        File parent = file.getParentFile();

        if (parent != null) {
            parent.mkdirs();
        }


        InputStream input =
                assetManager.open(path);

        FileOutputStream output =
                new FileOutputStream(file);


        byte[] buffer = new byte[8192];

        int len;

        while ((len = input.read(buffer)) != -1) {
            output.write(buffer,0,len);
        }


        output.flush();
        output.close();
        input.close();
    }



    public static void install(Context context) {

        try {

            SharedPreferences sp =
                    context.getSharedPreferences(
                            "bundled_skills",
                            0
                    );


            if (sp.getInt("installed_version",0) < 1) {


                copyDir(
                        context.getAssets(),
                        "skills",
                        new File(
                                context.getFilesDir(),
                                "skills"
                        )
                );


                sp.edit()
                        .putInt(
                                "installed_version",
                                1
                        )
                        .apply();
            }


        } catch(Throwable e) {

            Log.e(
                    "BundledSkillInstaller",
                    "install failed",
                    e
            );
        }
    }
}
