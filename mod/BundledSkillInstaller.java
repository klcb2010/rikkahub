package me.rerere.rikkahub;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class BundledSkillInstaller {

    private static final String TAG = "BundledSkillInstaller";
    private static final String PREF_NAME = "bundled_skills";
    private static final String KEY_VERSION = "installed_version";
    private static final int CURRENT_VERSION = 1;

    private static final String ASSET_SKILLS_DIR = "skills";
    private static final String TARGET_DIR = "skills";

    private BundledSkillInstaller() {
    }

    public static void install(Context context) {
    try {
        File skillsDir = new File(context.getFilesDir(), "skills");

        if (!skillsDir.exists()
                || skillsDir.listFiles() == null
                || skillsDir.listFiles().length == 0) {

            copyDir(
                context.getAssets(),
                "skills",
                skillsDir
            );

            Log.i(
                "BundledSkillInstaller",
                "skills copied: " + skillsDir
            );
        }

    } catch (Throwable e) {
        Log.e(
            "BundledSkillInstaller",
            "install failed",
            e
        );
    }
}

    private static void copyAssets(
            AssetManager assetManager,
            String assetPath,
            File targetDir
    ) throws IOException {

        String[] files =
                assetManager.list(assetPath);

        if (files == null) {
            return;
        }


        // 文件
        if (files.length == 0) {

            copyFile(
                    assetManager,
                    assetPath,
                    targetDir
            );

            return;
        }


        // 目录
        File dir =
                new File(
                        targetDir,
                        new File(assetPath).getName()
                );

        if (!dir.exists()) {
            dir.mkdirs();
        }


        for (String file : files) {

            copyAssets(
                    assetManager,
                    assetPath + "/" + file,
                    dir
            );
        }
    }


    private static void copyFile(
            AssetManager assetManager,
            String assetPath,
            File targetDir
    ) throws IOException {

        File outFile =
                new File(
                        targetDir,
                        new File(assetPath).getName()
                );


        try (
                InputStream input =
                        assetManager.open(assetPath);

                FileOutputStream output =
                        new FileOutputStream(outFile)
        ) {

            byte[] buffer = new byte[8192];

            int length;

            while ((length = input.read(buffer)) != -1) {

                output.write(
                        buffer,
                        0,
                        length
                );
            }

            output.flush();
        }
    }
}
