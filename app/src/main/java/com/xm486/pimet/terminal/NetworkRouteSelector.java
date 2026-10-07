package com.xm486.pimet.terminal;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 网络路线优选与镜像测速器：
 * 自动并发探测 GitHub 代理镜像与 npm 镜像源的延迟与连通性，自动匹配对当前用户网络最优的加速路线。
 */
public class NetworkRouteSelector {

    public static class RouteResult {
        public String fastestClawBenchUrl;
        public String clawBenchRouteName;
        public long clawBenchLatencyMs;

        public String fastestNpmRegistry;
        public String npmRegistryName;
        public long npmLatencyMs;

        @Override
        public String toString() {
            return "【下载路线】" + clawBenchRouteName + " (" + clawBenchLatencyMs + "ms) | 【npm 镜像】" + npmRegistryName + " (" + npmLatencyMs + "ms)";
        }
    }

    private static class ProbeCandidate {
        String name;
        String testUrl;
        String fullUrl;
        long latencyMs = Long.MAX_VALUE;

        ProbeCandidate(String name, String testUrl, String fullUrl) {
            this.name = name;
            this.testUrl = testUrl;
            this.fullUrl = fullUrl;
        }
    }

    private static final String DEFAULT_GITHUB_URL = "https://github.com/xulongzhe/clawbench/releases/latest/download/clawbench-linux-arm64";

    /**
     * 并发测试并选取最快路线（超时设为 2500ms）
     */
    public static RouteResult selectBestRoutes() {
        RouteResult result = new RouteResult();

        // 1. 准备 ClawBench 镜像候选
        List<ProbeCandidate> cbCandidates = new ArrayList<>();
        cbCandidates.add(new ProbeCandidate("官方 GitHub 直连", "https://github.com", DEFAULT_GITHUB_URL));
        cbCandidates.add(new ProbeCandidate("ghproxy.net 加速", "https://ghproxy.net", "https://ghproxy.net/" + DEFAULT_GITHUB_URL));
        cbCandidates.add(new ProbeCandidate("mirror.ghproxy.com 加速", "https://mirror.ghproxy.com", "https://mirror.ghproxy.com/" + DEFAULT_GITHUB_URL));
        cbCandidates.add(new ProbeCandidate("gh-proxy.com 加速", "https://gh-proxy.com", "https://gh-proxy.com/" + DEFAULT_GITHUB_URL));
        cbCandidates.add(new ProbeCandidate("ghproxy.cc 加速", "https://ghproxy.cc", "https://ghproxy.cc/" + DEFAULT_GITHUB_URL));

        // 2. 准备 npm 镜像候选
        List<ProbeCandidate> npmCandidates = new ArrayList<>();
        npmCandidates.add(new ProbeCandidate("淘宝/阿里 npm 镜像", "https://registry.npmmirror.com", "https://registry.npmmirror.com"));
        npmCandidates.add(new ProbeCandidate("腾讯云 npm 镜像", "https://mirrors.cloud.tencent.com/npm/", "https://mirrors.cloud.tencent.com/npm/"));
        npmCandidates.add(new ProbeCandidate("官方 npm 镜像", "https://registry.npmjs.org", "https://registry.npmjs.org"));

        ExecutorService pool = Executors.newFixedThreadPool(8);

        // 并发探测 ClawBench
        probeCandidates(pool, cbCandidates);
        // 并发探测 npm
        probeCandidates(pool, npmCandidates);

        pool.shutdown();
        try {
            pool.awaitTermination(3500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}

        // 筛选延迟最低的 ClawBench
        ProbeCandidate bestCb = findFastest(cbCandidates);
        if (bestCb != null && bestCb.latencyMs < Long.MAX_VALUE) {
            result.fastestClawBenchUrl = bestCb.fullUrl;
            result.clawBenchRouteName = bestCb.name;
            result.clawBenchLatencyMs = bestCb.latencyMs;
        } else {
            result.fastestClawBenchUrl = "https://ghproxy.net/" + DEFAULT_GITHUB_URL;
            result.clawBenchRouteName = "备用推荐线路 (ghproxy.net)";
            result.clawBenchLatencyMs = -1;
        }

        // 筛选延迟最低的 npm
        ProbeCandidate bestNpm = findFastest(npmCandidates);
        if (bestNpm != null && bestNpm.latencyMs < Long.MAX_VALUE) {
            result.fastestNpmRegistry = bestNpm.fullUrl;
            result.npmRegistryName = bestNpm.name;
            result.npmLatencyMs = bestNpm.latencyMs;
        } else {
            result.fastestNpmRegistry = "https://registry.npmmirror.com";
            result.npmRegistryName = "默认阿里镜像";
            result.npmLatencyMs = -1;
        }

        return result;
    }

    private static void probeCandidates(ExecutorService pool, List<ProbeCandidate> list) {
        for (ProbeCandidate c : list) {
            pool.execute(() -> {
                long start = System.currentTimeMillis();
                try {
                    URL url = new URL(c.testUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(2500);
                    conn.setReadTimeout(2500);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                    int code = conn.getResponseCode();
                    if (code > 0 && code < 500) {
                        c.latencyMs = System.currentTimeMillis() - start;
                    }
                    conn.disconnect();
                } catch (Throwable ignored) {
                    c.latencyMs = Long.MAX_VALUE;
                }
            });
        }
    }

    private static ProbeCandidate findFastest(List<ProbeCandidate> list) {
        ProbeCandidate best = null;
        for (ProbeCandidate c : list) {
            if (best == null || c.latencyMs < best.latencyMs) {
                best = c;
            }
        }
        return best;
    }
}
