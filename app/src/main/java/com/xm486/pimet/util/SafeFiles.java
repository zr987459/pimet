package com.xm486.pimet.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 安全删除：绝不跟随符号链接。
 *
 * Linux 容器 rootfs 里有大量绝对路径符号链接（如指向 /sdcard、/storage、/proc 的链接），
 * 用 File.isDirectory()/listFiles() 递归会穿过链接删到链接指向的宿主文件（例如用户的共享存储）。
 * 这里用 lstat（NOFOLLOW_LINKS）判断类型：符号链接只删链接本身；悬空链接也能删掉
 * （File.exists() 对悬空链接返回 false，旧实现会漏删导致父目录删不掉）。
 */
public final class SafeFiles {

    private SafeFiles() {}

    /** 递归删除文件或目录（不跟随符号链接）。全部删除成功返回 true；不存在也视为成功。 */
    public static boolean deleteTree(File f) {
        return f == null || deleteTree(f, 0);
    }

    private static boolean deleteTree(File f, int depth) {
        Path p = f.toPath();
        BasicFileAttributes a;
        try {
            a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
        boolean ok = true;
        if (a.isDirectory() && !a.isSymbolicLink() && depth < 512) {
            // 容器里有只读目录（如 0555），先补上本人权限才能删其中内容
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, true);
            //noinspection ResultOfMethodCallIgnored
            f.setWritable(true, true);
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, true);
            String[] names = f.list();
            if (names != null) {
                for (String n : names) {
                    ok &= deleteTree(new File(f, n), depth + 1);
                }
            }
        }
        try {
            Files.deleteIfExists(p); // 对符号链接只删除链接本身
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return ok;
    }

    /** 是否为符号链接（不跟随）。 */
    public static boolean isSymlink(File f) {
        try {
            return f != null && Files.isSymbolicLink(f.toPath());
        } catch (Throwable t) {
            return false;
        }
    }
}
