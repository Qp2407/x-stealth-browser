package com.xstealth.browser;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String PROFILE_PATH = "/data/local/tmp/x_stealth_profile.json";
    private static final String COOKIE_DUMP_PATH = "/data/local/tmp/x_stealth_cookies.txt";
    private static final String PREFS_NAME = "x_stealth_prefs";
    private static final String DEFAULT_URL = "https://x.com/login/signup";

    // Built-in 12 Mobile WebGL & Device Metadata Pool: {brand, model, android_ver, gpu_vendor, gpu_renderer, gpu_arch, cores, ram_gb (clamped to Chromium max 8)}
    private static final String[][] BUILTIN_WEBGL_PROFILES = new String[][]{
        {"Samsung", "SM-S928B", "14", "Qualcomm", "Adreno (TM) 750", "adreno-7xx", "8", "8"},
        {"Samsung", "SM-S918B", "14", "Qualcomm", "Adreno (TM) 740", "adreno-7xx", "8", "8"},
        {"Google", "Pixel 8 Pro", "14", "ARM", "Mali-G715-Immortalis MC11", "valhall", "8", "8"},
        {"Xiaomi", "23127PN0CG", "14", "Qualcomm", "Adreno (TM) 750", "adreno-7xx", "8", "8"},
        {"OnePlus", "CPH2581", "14", "Qualcomm", "Adreno (TM) 750", "adreno-7xx", "8", "8"},
        {"Samsung", "SM-S921B", "14", "Samsung Electronics Co., Ltd.", "Samsung Xclipse 940", "rdna3", "8", "8"},
        {"Samsung", "SM-S908B", "14", "Qualcomm", "Adreno (TM) 730", "adreno-7xx", "8", "8"},
        {"Google", "Pixel 7 Pro", "14", "ARM", "Mali-G710 MC10", "valhall", "8", "8"},
        {"Samsung", "SM-A546E", "14", "ARM", "Mali-G68 MC4", "valhall", "8", "8"},
        {"Xiaomi", "2211133G", "14", "Qualcomm", "Adreno (TM) 740", "adreno-7xx", "8", "8"},
        {"Samsung", "SM-G998B", "14", "ARM", "Mali-G78", "valhall", "8", "8"},
        {"OnePlus", "NE2213", "14", "Qualcomm", "Adreno (TM) 730", "adreno-7xx", "8", "8"}
    };

    private WebView webView;
    private TextView urlBar;
    private TextView deviceBadge;
    private JSONObject currentProfile = new JSONObject();
    private String compiledStealthJs = "";
    private String resolvedUserAgent = "";
    private String resolvedChromeVer = "135.0.7049.38";
    private String resolvedChromeMajor = "135";
    private Object docStartScriptHandler = null;

    @Override
    public String getPackageName() {
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 0; i < stack.length; i++) {
                String cls = stack[i].getClassName();
                if (cls != null && (cls.startsWith("org.chromium.") || cls.startsWith("android.webkit.") || cls.startsWith("com.android.webview."))) {
                    return "com.android.chrome";
                }
            }
        } catch (Exception ignored) {
        }
        return super.getPackageName();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        deviceBadge = (TextView) findViewById(R.id.device_badge);
        urlBar = (TextView) findViewById(R.id.url_bar);
        webView = (WebView) findViewById(R.id.webview);

        if (deviceBadge != null) {
            deviceBadge.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rotateWebGlProfile();
                }
            });
        }

        loadStealthProfile();
        configureWebView();

        String startUrl = resolveTargetUrl(getIntent(), true);
        applyPreFlightCookies();
        navigateToUrl(startUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            String jsAction = intent.getStringExtra("js_action");
            if ("click_continue".equals(jsAction)) {
                performDomContinueTap();
                return;
            }
            if ("rotate_webgl".equals(jsAction)) {
                rotateWebGlProfile();
                return;
            }
            if ("eval_js".equals(jsAction)) {
                String code = intent.getStringExtra("js_code");
                if (code != null && !code.isEmpty() && webView != null) {
                    webView.evaluateJavascript(code, null);
                }
                return;
            }
        }
        loadStealthProfile();
        applyProfileToWebSettings();
        applyPreFlightCookies();
        String targetUrl = resolveTargetUrl(intent, false);
        if (targetUrl != null && !targetUrl.isEmpty()) {
            navigateToUrl(targetUrl);
        }
    }

    private String resolveTargetUrl(Intent intent, boolean fallbackToDefault) {
        if (intent == null) {
            return fallbackToDefault ? DEFAULT_URL : null;
        }
        Uri data = intent.getData();
        if (data != null) {
            String u = data.toString().trim();
            if (!u.isEmpty()) {
                return u;
            }
        }
        String extraUrl = intent.getStringExtra("url");
        if (extraUrl != null && !extraUrl.trim().isEmpty()) {
            return extraUrl.trim();
        }
        return fallbackToDefault ? DEFAULT_URL : null;
    }

    private void performDomContinueTap() {
        if (webView == null) return;
        String tapFinderJs = "(function(){"
            + "try{"
            + "var active=document.activeElement;"
            + "if(active&&(active.tagName==='INPUT'||active.tagName==='TEXTAREA')){"
            + "active.dispatchEvent(new Event('input',{bubbles:true}));"
            + "active.dispatchEvent(new Event('change',{bubbles:true}));"
            + "}"
            + "var inputs=document.querySelectorAll('input.jf-phone-input,input[type=\"tel\"],input[type=\"email\"],input[type=\"text\"]');"
            + "for(var k=0;k<inputs.length;k++){"
            + "var inp=inputs[k];"
            + "if(inp&&inp.value){"
            + "inp.dispatchEvent(new Event('input',{bubbles:true}));"
            + "inp.dispatchEvent(new Event('change',{bubbles:true}));"
            + "}"
            + "}"
            + "var cands=Array.from(document.querySelectorAll('button,[role=\"button\"],input[type=\"submit\"]'));"
            + "var target=null;"
            + "for(var i=cands.length-1;i>=0;i--){"
            + "var el=cands[i];"
            + "var r=el.getBoundingClientRect();"
            + "if(r.width<36||r.height<18||r.top<40)continue;"
            + "var txt=(el.innerText||el.textContent||el.value||el.getAttribute('aria-label')||'').trim().toLowerCase();"
            + "if((txt==='continue'||txt==='next'||txt==='tiếp tục'||txt==='tiếp theo'||txt==='ok'||txt==='verify'||txt==='sign up')&&txt.indexOf('with')===-1&&txt.indexOf('google')===-1&&txt.indexOf('apple')===-1){"
            + "target=el;break;"
            + "}"
            + "}"
            + "if(!target)target=document.querySelector('button[type=\"submit\"]');"
            + "if(!target)return JSON.stringify({found:false});"
            + "var rect=target.getBoundingClientRect();"
            + "if(rect.top<0||rect.bottom>window.innerHeight){"
            + "target.scrollIntoView({block:'center',inline:'center'});"
            + "rect=target.getBoundingClientRect();"
            + "}"
            + "return JSON.stringify({"
            + "found:true,"
            + "cx:rect.left+(rect.width/2.0),"
            + "cy:rect.top+(rect.height/2.0),"
            + "vw:window.innerWidth||360,"
            + "vh:window.innerHeight||566"
            + "});"
            + "}catch(e){return JSON.stringify({found:false,err:String(e)});}"
            + "})();";

        webView.evaluateJavascript(tapFinderJs, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                try {
                    if (value == null || value.isEmpty() || "null".equals(value)) return;
                    String raw = value;
                    if (raw.startsWith("\"") && raw.endsWith("\"")) {
                        raw = raw.substring(1, raw.length() - 1)
                                 .replace("\\\"", "\"")
                                 .replace("\\\\", "\\");
                    }
                    JSONObject obj = new JSONObject(raw);
                    if (!obj.optBoolean("found", false)) return;
                    double cx = obj.optDouble("cx", 180.0);
                    double cy = obj.optDouble("cy", 450.0);
                    double vw = Math.max(1.0, obj.optDouble("vw", 360.0));
                    double vh = Math.max(1.0, obj.optDouble("vh", 566.0));

                    int w = webView.getWidth();
                    int h = webView.getHeight();
                    if (w <= 0) w = 360;
                    if (h <= 0) h = 566;

                    final float touchX = (float) (cx * w / vw);
                    final float touchY = (float) (cy * h / vh);
                    final long downTime = SystemClock.uptimeMillis();

                    final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
                    MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
                    pp.id = 0;
                    pp.toolType = MotionEvent.TOOL_TYPE_FINGER;
                    props[0] = pp;

                    MotionEvent.PointerCoords pcDown = new MotionEvent.PointerCoords();
                    pcDown.x = touchX;
                    pcDown.y = touchY;
                    pcDown.pressure = 0.56f;
                    pcDown.size = 0.22f;
                    pcDown.touchMajor = 24.0f;
                    pcDown.touchMinor = 19.0f;
                    pcDown.toolMajor = 24.0f;
                    pcDown.toolMinor = 19.0f;

                    MotionEvent downEvt = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, 1, props,
                        new MotionEvent.PointerCoords[]{pcDown}, 0, 0, 1.0f, 1.0f, 4, 0, 0x1002, 0
                    );
                    webView.dispatchTouchEvent(downEvt);
                    downEvt.recycle();

                    webView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                long upTime = SystemClock.uptimeMillis();
                                MotionEvent.PointerCoords pcUp = new MotionEvent.PointerCoords();
                                pcUp.x = touchX + 0.35f;
                                pcUp.y = touchY + 0.25f;
                                pcUp.pressure = 0.48f;
                                pcUp.size = 0.20f;
                                pcUp.touchMajor = 22.0f;
                                pcUp.touchMinor = 18.0f;
                                pcUp.toolMajor = 22.0f;
                                pcUp.toolMinor = 18.0f;
                                MotionEvent upEvt = MotionEvent.obtain(
                                    downTime, upTime, MotionEvent.ACTION_UP, 1, props,
                                    new MotionEvent.PointerCoords[]{pcUp}, 0, 0, 1.0f, 1.0f, 4, 0, 0x1002, 0
                                );
                                webView.dispatchTouchEvent(upEvt);
                                upEvt.recycle();
                            } catch (Exception ignored) {
                            }
                            webView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        webView.evaluateJavascript(
                                            "(function(){try{var cands=Array.from(document.querySelectorAll('button,[role=\"button\"],input[type=\"submit\"]'));var t=null;for(var i=cands.length-1;i>=0;i--){var el=cands[i];var r=el.getBoundingClientRect();if(r.width<36||r.height<18||r.top<40)continue;var txt=(el.innerText||el.textContent||el.value||el.getAttribute('aria-label')||'').trim().toLowerCase();if((txt==='continue'||txt==='next'||txt==='tiếp tục'||txt==='tiếp theo'||txt==='ok'||txt==='verify'||txt==='sign up')&&txt.indexOf('with')===-1&&txt.indexOf('google')===-1&&txt.indexOf('apple')===-1){t=el;break;}}if(!t)t=document.querySelector('button[type=\"submit\"]');if(t&&!t.disabled){var b=t.getBoundingClientRect();var mx=b.left+b.width/2,my=b.top+b.height/2;t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,clientX:mx,clientY:my,button:0,buttons:1}));t.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,clientX:mx,clientY:my,button:0,buttons:0}));t.click();}}catch(e){}})();",
                                            null
                                        );
                                    } catch (Exception ignored) {
                                    }
                                }
                            }, 65);
                        }
                    }, 48);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void resolveUnifiedUserAgent() {
        String nativeChrome = "";
        int nativeMajor = 0;
        try {
            String sysUa = WebSettings.getDefaultUserAgent(this);
            if (sysUa != null) {
                int idx = sysUa.indexOf("Chrome/");
                if (idx != -1) {
                    int end = sysUa.indexOf(' ', idx);
                    if (end == -1) end = sysUa.length();
                    String ver = sysUa.substring(idx + 7, end).trim();
                    int dot = ver.indexOf('.');
                    if (dot > 0) {
                        int mj = Integer.parseInt(ver.substring(0, dot));
                        if (mj >= 115) {
                            nativeChrome = ver;
                            nativeMajor = mj;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String profVer = currentProfile.optString("chrome_version", "");
        String profMaj = currentProfile.optString("chrome_major", "");
        if (nativeMajor >= 120) {
            resolvedChromeVer = nativeChrome;
            resolvedChromeMajor = String.valueOf(nativeMajor);
        } else if (profVer != null && !profVer.trim().isEmpty()) {
            resolvedChromeVer = profVer.trim();
            int dot = resolvedChromeVer.indexOf('.');
            resolvedChromeMajor = (profMaj != null && !profMaj.trim().isEmpty())
                ? profMaj.trim()
                : (dot > 0 ? resolvedChromeVer.substring(0, dot) : "135");
        } else if (nativeMajor >= 115) {
            resolvedChromeVer = nativeChrome;
            resolvedChromeMajor = String.valueOf(nativeMajor);
        } else {
            resolvedChromeVer = "135.0.7049.38";
            resolvedChromeMajor = "135";
        }
        String mdl = currentProfile.optString("model", "SM-S928B");
        String andVer = currentProfile.optString("android_ver", "14");
        // Chrome 110+ Android User-Agent Reduction spec: UA header & navigator.userAgent always use "Android 10; K" and "Chrome/<major>.0.0.0"
        // while device model (e.g. SM-S928B, NE2213) & full build version are exposed via userAgentData.getHighEntropyValues() & Sec-CH-UA
        resolvedUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
            + resolvedChromeMajor + ".0.0.0 Mobile Safari/537.36";
    }

    private JSONObject buildStandaloneProfile(int idx) {
        JSONObject p = new JSONObject();
        try {
            Random rng = new Random();
            int safeIdx = Math.abs(idx) % BUILTIN_WEBGL_PROFILES.length;
            String[] row = BUILTIN_WEBGL_PROFILES[safeIdx];
            p.put("brand", row[0]);
            p.put("model", row[1]);
            p.put("android_ver", row[2]);
            p.put("gpu_vendor", row[3]);
            p.put("gpu_renderer", row[4]);
            p.put("gpu_arch", row[5]);
            p.put("cores", Integer.parseInt(row[6]));
            p.put("ram_gb", Integer.parseInt(row[7]));
            p.put("platform", "Linux armv81");
            p.put("cuid", UUID.randomUUID().toString());
            p.put("canvas_seed", 0x10000000L + (Math.abs(rng.nextLong()) % 0x7FFFFFFFL));
            p.put("audio_seed", 0x10000000L + (Math.abs(rng.nextLong()) % 0x7FFFFFFFL));
            p.put("canvas_noise_r", rng.nextBoolean() ? 1 : -1);
            p.put("canvas_noise_g", rng.nextBoolean() ? -1 : 1);
            p.put("canvas_noise_b", rng.nextBoolean() ? 1 : -1);
            int[] heights = new int[]{780, 800, 812, 824, 844, 854};
            int sh = heights[rng.nextInt(heights.length)];
            p.put("screen_height", sh);
            p.put("avail_height", sh);
            double[] dprs = new double[]{2.75, 3.0, 3.25, 3.5};
            p.put("device_pixel_ratio", dprs[rng.nextInt(dprs.length)]);
            p.put("lan_ip", "192.168.1." + (101 + rng.nextInt(90)));
            p.put("conn_rtt", 35 + rng.nextInt(45));
            p.put("conn_downlink", 8.5 + (rng.nextInt(60) / 10.0));
            p.put("battery_level", 0.42 + (rng.nextInt(48) / 100.0));
            p.put("discharging_time", 7200 + rng.nextInt(10800));
        } catch (Exception ignored) {
        }
        return p;
    }

    private void rotateWebGlProfile() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            int nextIdx = (prefs.getInt("webgl_idx", 0) + 1) % BUILTIN_WEBGL_PROFILES.length;
            JSONObject fresh = buildStandaloneProfile(nextIdx);
            prefs.edit()
                 .putInt("webgl_idx", nextIdx)
                 .putString("standalone_profile", fresh.toString())
                 .apply();
            currentProfile = fresh;
            resolveUnifiedUserAgent();
            updateBadgeView();
            compiledStealthJs = buildStealthJavaScript(currentProfile);
            applyProfileToWebSettings();

            try {
                CookieManager cm = CookieManager.getInstance();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.removeAllCookies(null);
                    cm.flush();
                } else {
                    cm.removeAllCookie();
                }
            } catch (Exception ignored) {
            }
            try {
                WebStorage.getInstance().deleteAllData();
            } catch (Exception ignored) {
            }
            applyPreFlightCookies();
            if (webView != null) {
                webView.clearCache(true);
                webView.clearHistory();
                navigateToUrl(DEFAULT_URL);
            }
            String mdl = currentProfile.optString("model", "SM-S928B");
            String gVen = currentProfile.optString("gpu_vendor", "Qualcomm");
            String gRen = currentProfile.optString("gpu_renderer", "Adreno (TM) 750");
            Toast.makeText(this, "WebGL Metadata: " + mdl + " | " + gVen + " | " + gRen, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private void updateBadgeView() {
        if (deviceBadge != null) {
            String mdl = currentProfile.optString("model", "SM-S928B");
            String gpu = currentProfile.optString("gpu_renderer", "Adreno 750")
                .replace("Adreno (TM) ", "Adreno ")
                .replace("-Immortalis", "")
                .replace("Samsung ", "");
            deviceBadge.setText("● " + mdl + " • " + gpu + " ↻");
        }
    }

    private void loadStealthProfile() {
        boolean loadedFromFile = false;
        try {
            File f = new File(PROFILE_PATH);
            if (f.exists() && f.canRead()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)
                );
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                br.close();
                String raw = sb.toString().trim();
                if (!raw.isEmpty()) {
                    currentProfile = new JSONObject(raw);
                    loadedFromFile = true;
                }
            }
        } catch (Exception ignored) {
        }
        if (!loadedFromFile) {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                String saved = prefs.getString("standalone_profile", "");
                if (saved != null && !saved.isEmpty()) {
                    currentProfile = new JSONObject(saved);
                } else {
                    int idx = new Random().nextInt(BUILTIN_WEBGL_PROFILES.length);
                    currentProfile = buildStandaloneProfile(idx);
                    prefs.edit()
                         .putInt("webgl_idx", idx)
                         .putString("standalone_profile", currentProfile.toString())
                         .apply();
                }
            } catch (Exception ignored) {
                currentProfile = buildStandaloneProfile(0);
            }
        }
        resolveUnifiedUserAgent();
        updateBadgeView();
        compiledStealthJs = buildStealthJavaScript(currentProfile);
    }

    private void registerDocumentStartStealth() {
        if (webView == null || compiledStealthJs == null || compiledStealthJs.isEmpty()) return;
        try {
            if (docStartScriptHandler != null) {
                try {
                    Method rm = docStartScriptHandler.getClass().getDeclaredMethod("remove");
                    rm.setAccessible(true);
                    rm.invoke(docStartScriptHandler);
                } catch (Throwable ignored) {
                }
                docStartScriptHandler = null;
            }
            Field fProv = WebView.class.getDeclaredField("mProvider");
            fProv.setAccessible(true);
            Object provider = fProv.get(webView);
            if (provider != null) {
                Field fAw = provider.getClass().getDeclaredField("mAwContents");
                fAw.setAccessible(true);
                Object awContents = fAw.get(provider);
                if (awContents != null) {
                    Method addJs = awContents.getClass().getDeclaredMethod(
                        "addDocumentStartJavaScript", String.class, String[].class
                    );
                    addJs.setAccessible(true);
                    docStartScriptHandler = addJs.invoke(awContents, compiledStealthJs, new String[]{"*"});
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void applyNativeClientHintsMetadata(WebSettings s) {
        try {
            Field fAwSettings = s.getClass().getDeclaredField("mAwSettings");
            fAwSettings.setAccessible(true);
            Object awSettings = fAwSettings.get(s);
            if (awSettings == null) return;
            Method setUaMeta = awSettings.getClass().getDeclaredMethod("setUserAgentMetadataFromMap", Map.class);
            setUaMeta.setAccessible(true);

            String mdl = currentProfile.optString("model", "SM-S928B");
            String andVer = currentProfile.optString("android_ver", "14");
            Map<String, Object> meta = new HashMap<String, Object>();
            String[][] brands = new String[][]{
                {"Google Chrome", resolvedChromeMajor, resolvedChromeVer},
                {"Chromium", resolvedChromeMajor, resolvedChromeVer},
                {"Not-A.Brand", "24", "24.0.0.0"}
            };
            meta.put("BRAND_VERSION_LIST", brands);
            meta.put("FULL_VERSION", resolvedChromeVer);
            meta.put("PLATFORM", "Android");
            meta.put("PLATFORM_VERSION", andVer + ".0.0");
            meta.put("ARCHITECTURE", "arm");
            meta.put("MODEL", mdl);
            meta.put("MOBILE", Boolean.TRUE);
            meta.put("BITNESS", Integer.valueOf(64));
            meta.put("WOW64", Boolean.FALSE);
            meta.put("FORM_FACTORS", new String[]{"Mobile"});
            setUaMeta.invoke(awSettings, meta);
        } catch (Throwable ignored) {
        }
    }

    private void applyProfileToWebSettings() {
        if (webView == null) return;
        if (resolvedUserAgent == null || resolvedUserAgent.isEmpty()) {
            resolveUnifiedUserAgent();
        }
        WebSettings s = webView.getSettings();
        s.setUserAgentString(resolvedUserAgent);
        applyNativeClientHintsMetadata(s);
        registerDocumentStartStealth();
    }

    private void applyPreFlightCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (webView != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
            String cuid = currentProfile.optString("cuid", "");
            if (cuid != null && !cuid.isEmpty()) {
                cm.setCookie("https://x.com", "__cuid=" + cuid + "; Domain=.x.com; Path=/; Secure; SameSite=Lax");
                cm.setCookie("https://twitter.com", "__cuid=" + cuid + "; Domain=.twitter.com; Path=/; Secure; SameSite=Lax");
            }
            JSONObject cookies = currentProfile.optJSONObject("cookies");
            if (cookies != null) {
                Iterator<String> keys = cookies.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = cookies.optString(k, "");
                    if (k != null && !k.isEmpty() && v != null && !v.isEmpty()) {
                        boolean httpOnly = "auth_token".equals(k) || "kdt".equals(k) || "att".equals(k);
                        String tail = "; Path=/; Secure; SameSite=Lax" + (httpOnly ? "; HttpOnly" : "");
                        cm.setCookie("https://x.com", k + "=" + v + "; Domain=.x.com" + tail);
                        cm.setCookie("https://twitter.com", k + "=" + v + "; Domain=.twitter.com" + tail);
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void dumpCookiesToDisk() {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.flush();
            }
            String xCookies = cm.getCookie("https://x.com");
            String twCookies = cm.getCookie("https://twitter.com");
            StringBuilder out = new StringBuilder();
            if (xCookies != null && !xCookies.isEmpty()) {
                out.append(xCookies);
            }
            if (twCookies != null && !twCookies.isEmpty()) {
                if (out.length() > 0) out.append("; ");
                out.append(twCookies);
            }
            byte[] data = out.toString().getBytes(StandardCharsets.UTF_8);
            try {
                File fInternal = new File(getFilesDir(), "x_stealth_cookies.txt");
                FileOutputStream fosInt = new FileOutputStream(fInternal, false);
                fosInt.write(data);
                fosInt.flush();
                fosInt.close();
                fInternal.setReadable(true, false);
            } catch (Exception ignored) {
            }
            try {
                File f = new File(COOKIE_DUMP_PATH);
                FileOutputStream fos = new FileOutputStream(f, false);
                fos.write(data);
                fos.flush();
                fos.close();
                f.setReadable(true, false);
                f.setWritable(true, false);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    private void updateUrlBarText(String url) {
        if (urlBar != null && url != null && !url.isEmpty()) {
            urlBar.setText(url);
        }
    }

    private void injectStealthNow(WebView view) {
        if (view != null && compiledStealthJs != null && !compiledStealthJs.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                view.evaluateJavascript(compiledStealthJs, null);
            } else {
                view.loadUrl("javascript:" + compiledStealthJs);
            }
        }
    }

    private void configureWebView() {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(webView, true);
            }
        } catch (Exception ignored) {
        }
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSaveFormData(false);
        s.setSavePassword(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            s.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        applyProfileToWebSettings();

        webView.setBackgroundColor(Color.BLACK);
        webView.setWebChromeClient(new StealthChromeClient(this));
        webView.setWebViewClient(new StealthViewClient(this));
    }

    private static class StealthChromeClient extends WebChromeClient {
        private final MainActivity act;
        StealthChromeClient(MainActivity a) { this.act = a; }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (newProgress <= 35 || newProgress == 100) {
                act.injectStealthNow(view);
            }
            String u = view.getUrl();
            if (u != null) {
                act.updateUrlBarText(u);
            }
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage cm) {
            if (cm != null && cm.message() != null) {
                Log.i("XSTEALTH_JS", cm.message() + " [" + cm.sourceId() + ":" + cm.lineNumber() + "]");
            }
            return super.onConsoleMessage(cm);
        }
    }

    private static class StealthViewClient extends WebViewClient {
        private final MainActivity act;
        StealthViewClient(MainActivity a) { this.act = a; }

        @Override
        public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && view != null) {
                if (request.isForMainFrame()) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            act.injectStealthNow(view);
                        }
                    });
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null) return false;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                act.updateUrlBarText(url);
                return false;
            }
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request != null && request.getUrl() != null) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    if (request.isForMainFrame()) {
                        act.updateUrlBarText(u);
                    }
                    return false;
                }
                return true;
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            act.updateUrlBarText(url);
            act.injectStealthNow(view);
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            super.onLoadResource(view, url);
            act.injectStealthNow(view);
            String cur = view.getUrl();
            if (cur != null) {
                act.updateUrlBarText(cur);
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            act.injectStealthNow(view);
            act.updateUrlBarText(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            act.updateUrlBarText(url);
            act.injectStealthNow(view);
            act.dumpCookiesToDisk();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            act.updateUrlBarText(url);
            act.injectStealthNow(view);
            act.dumpCookiesToDisk();
        }
    }

    private void navigateToUrl(String url) {
        if (webView == null) return;
        String clean = (url == null || url.trim().isEmpty()) ? DEFAULT_URL : url.trim();
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://" + clean;
        }
        updateUrlBarText(clean);
        webView.loadUrl(clean);
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private String buildStealthJavaScript(JSONObject p) {
        String brand = jsEscape(p.optString("brand", "Samsung"));
        String model = jsEscape(p.optString("model", "SM-S928B"));
        String androidVer = jsEscape(p.optString("android_ver", "14"));
        String gpuVendor = jsEscape(p.optString("gpu_vendor", "Qualcomm"));
        String gpuRenderer = jsEscape(p.optString("gpu_renderer", "Adreno (TM) 750"));
        String gpuVersion = jsEscape(p.optString("gpu_version", "OpenGL ES 3.2 V@0762.0"));
        String gpuArch = jsEscape(p.optString("gpu_arch", "adreno-7xx"));
        String chromeVer = jsEscape(resolvedChromeVer);
        String chromeMajor = jsEscape(resolvedChromeMajor);
        String platform = jsEscape(p.optString("platform", "Linux armv81"));
        String lanIp = jsEscape(p.optString("lan_ip", "192.168.1.104"));
        String clientIp = jsEscape(p.optString("client_ip", ""));
        String camBackId = jsEscape(p.optString("cam_back_id", "8f3c1e7a9b2d4f601538a7c9e2b4d6f80123456789abcdef0123456789abcdef"));
        String camFrontId = jsEscape(p.optString("cam_front_id", "4a9e2b7c1d5f80369247b1e5c8a3f6d90fedcba9876543210fedcba987654321"));
        String micId = jsEscape(p.optString("mic_id", "c7b2e5a9f1d480631952e7b4a8c1d5f301928374655647382910fedcba987654"));
        String mediaGroupId = jsEscape(p.optString("media_group_id", "e1d9a4c7b3f680259148c3e7a2b5d8f10a1b2c3d4e5f60718293a4b5c6d7e8f9"));
        String ua = jsEscape(resolvedUserAgent);
        int cores = p.optInt("cores", 8);
        int rawRam = p.optInt("ram_gb", 8);
        // Chromium ApproximatedDeviceMemory clamps navigator.deviceMemory to powers of 2 capped at 8 (never 12 or 16)
        int ramGb = (rawRam >= 8) ? 8 : (rawRam >= 4 ? 4 : 2);
        int noiseR = p.optInt("canvas_noise_r", 1);
        int noiseG = p.optInt("canvas_noise_g", -1);
        int noiseB = p.optInt("canvas_noise_b", 1);
        long canvasSeed = p.optLong("canvas_seed", 0x5A17C3E9L);
        long audioSeed = p.optLong("audio_seed", 0x9E3779B1L);
        long storageQuota = p.optLong("storage_quota", 256000000000L);
        long storageUsage = p.optLong("storage_usage", 42158080L);
        int screenHeight = p.optInt("screen_height", 800);
        int availHeight = p.optInt("avail_height", 800);
        double dpr = p.optDouble("device_pixel_ratio", 3.0);
        int connRtt = p.optInt("conn_rtt", 50);
        double connDownlink = p.optDouble("conn_downlink", 10.0);
        double battLevel = p.optDouble("battery_level", 0.74);
        int disTime = p.optInt("discharging_time", 10800);

        return "(function(){"
            + "var _alr=false;try{_alr=(Function.prototype.toString.call('__x_s7__')==='__x_s7__');}catch(e){}if(_alr)return;"
            + "try{"
            + "const nativeMap=new WeakMap();"
            + "const patchedSet=new WeakSet();"
            + "const origToString=Function.prototype.toString;"
            + "const origCaptureStackTrace=Error.captureStackTrace;"
            + "const makeNative=function(name,fn){"
            + "const sname=String(name||'');"
            + "const m=({[sname](){return fn.apply(this,arguments);}})[sname];"
            + "nativeMap.set(m,'function '+sname+'() { [native code] }');"
            + "return m;"
            + "};"
            + "const markNative=function(fn,name){return makeNative(name||'',fn);};"
            + "Function.prototype.toString=makeNative('toString',function(){"
            + "if(this==='__x_s7__')return '__x_s7__';"
            + "if(typeof this!=='function')return origToString.apply(this,arguments);"
            + "return nativeMap.has(this)?nativeMap.get(this):origToString.call(this);"
            + "});"
            + "const defProp=function(obj,prop,getter){try{Object.defineProperty(obj,prop,{get:makeNative('get '+prop,getter),configurable:true,enumerable:true});}catch(e){}};"
            + "const defProto=function(proto,prop,getter){try{"
            + "if(!proto)return;"
            + "const gname='get '+prop;"
            + "const ctor=proto.constructor;"
            + "const g=({[gname](){"
            + "if(!this||this===proto||(typeof ctor==='function'&&!(this instanceof ctor))){"
            + "const err=new TypeError('Illegal invocation');"
            + "if(origCaptureStackTrace)origCaptureStackTrace(err,g);"
            + "throw err;"
            + "}"
            + "return getter.call(this);"
            + "}})[gname];"
            + "nativeMap.set(g,'function '+gname+'() { [native code] }');"
            + "Object.defineProperty(proto,prop,{get:g,configurable:true,enumerable:true});"
            + "}catch(e){}};"
            // 1. Modern Chrome 100-135 Polyfills for JetFuel (C3KtfKl5.js, QM5xmrcI.js, BDVUXFTy.js, B8xz5lyW.js) & Castle (D_8ZVxRh.js)
            + "if(typeof globalThis==='undefined'){window.globalThis=window;}"
            + "if(!window.requestIdleCallback){window.requestIdleCallback=markNative(function(cb){var s=Date.now();return setTimeout(function(){cb({didTimeout:false,timeRemaining:function(){return Math.max(0,50-(Date.now()-s));}});},1);},'requestIdleCallback');}"
            + "if(!window.cancelIdleCallback){window.cancelIdleCallback=markNative(function(id){clearTimeout(id);},'cancelIdleCallback');}"
            + "if(window.URL){"
            + "if(!URL.parse){URL.parse=markNative(function(u,b){try{return b!==undefined?new URL(u,b):new URL(u);}catch(e){return null;}},'parse');}"
            + "if(!URL.canParse){URL.canParse=markNative(function(u,b){try{b!==undefined?new URL(u,b):new URL(u);return true;}catch(e){return false;}},'canParse');}"
            + "}"
            + "if(!Promise.withResolvers){Promise.withResolvers=markNative(function(){var res,rej,p=new this(function(a,b){res=a;rej=b;});return {promise:p,resolve:res,reject:rej};},'withResolvers');}"
            + "if(!Promise.allSettled){Promise.allSettled=markNative(function(it){return Promise.all(Array.from(it).map(function(p){return Promise.resolve(p).then(function(v){return {status:'fulfilled',value:v};},function(r){return {status:'rejected',reason:r};});}));},'allSettled');}"
            + "if(!Error.isError){Error.isError=markNative(function(e){return e instanceof Error||Object.prototype.toString.call(e)==='[object Error]'||Object.prototype.toString.call(e)==='[object DOMException]';},'isError');}"
            + "if(window.AbortSignal){"
            + "if(!AbortSignal.timeout){AbortSignal.timeout=markNative(function(ms){var c=new AbortController();setTimeout(function(){try{c.abort(new DOMException('The operation was aborted due to timeout','TimeoutError'));}catch(e){c.abort();}},ms);return c.signal;},'timeout');}"
            + "if(!AbortSignal.any){AbortSignal.any=markNative(function(sigs){var c=new AbortController();for(var i=0;i<sigs.length;i++){var s=sigs[i];if(s&&s.aborted){c.abort(s.reason);return c.signal;}if(s)s.addEventListener('abort',function(){c.abort(this.reason);},{once:true});}return c.signal;},'any');}"
            + "if(!AbortSignal.prototype.throwIfAborted){AbortSignal.prototype.throwIfAborted=markNative(function(){if(this.aborted){throw this.reason||new DOMException('The operation was aborted','AbortError');}},'throwIfAborted');}"
            + "}"
            + "if(window.crypto&&!window.crypto.randomUUID){"
            + "window.crypto.randomUUID=markNative(function(){"
            + "var b=new Uint8Array(16);window.crypto.getRandomValues(b);"
            + "b[6]=(b[6]&0x0f)|0x40;b[8]=(b[8]&0x3f)|0x80;"
            + "var h=[];for(var i=0;i<16;i++)h.push((b[i]+0x100).toString(16).slice(1));"
            + "return h[0]+h[1]+h[2]+h[3]+'-'+h[4]+h[5]+'-'+h[6]+h[7]+'-'+h[8]+h[9]+'-'+h[10]+h[11]+h[12]+h[13]+h[14]+h[15];"
            + "},'randomUUID');"
            + "}"
            + "if(!Object.hasOwn){Object.hasOwn=markNative(function(o,k){return Object.prototype.hasOwnProperty.call(Object(o),k);},'hasOwn');}"
            + "if(!Object.groupBy){Object.groupBy=markNative(function(items,cb){var o=Object.create(null),i=0;for(var x of items){var k=cb(x,i++);(o[k]||(o[k]=[])).push(x);}return o;},'groupBy');}"
            + "if(window.Map&&!Map.groupBy){Map.groupBy=markNative(function(items,cb){var m=new Map(),i=0;for(var x of items){var k=cb(x,i++);var l=m.get(k);if(!l){l=[];m.set(k,l);}l.push(x);}return m;},'groupBy');}"
            + "if(!Array.prototype.at){Array.prototype.at=markNative(function(n){n=Math.trunc(n)||0;if(n<0)n+=this.length;return(n<0||n>=this.length)?undefined:this[n];},'at');}"
            + "if(!String.prototype.at){String.prototype.at=markNative(function(n){n=Math.trunc(n)||0;if(n<0)n+=this.length;return(n<0||n>=this.length)?undefined:this.charAt(n);},'at');}"
            + "if(!Array.prototype.toSorted){Array.prototype.toSorted=markNative(function(fn){return Array.prototype.slice.call(this).sort(fn);},'toSorted');}"
            + "if(!Array.prototype.toReversed){Array.prototype.toReversed=markNative(function(){return Array.prototype.slice.call(this).reverse();},'toReversed');}"
            + "if(!Array.prototype.toSpliced){Array.prototype.toSpliced=markNative(function(){var c=Array.prototype.slice.call(this);Array.prototype.splice.apply(c,arguments);return c;},'toSpliced');}"
            + "if(!Array.prototype.findLast){Array.prototype.findLast=markNative(function(fn,t){for(var i=this.length-1;i>=0;i--){if(fn.call(t,this[i],i,this))return this[i];}return undefined;},'findLast');}"
            + "if(!Array.prototype.findLastIndex){Array.prototype.findLastIndex=markNative(function(fn,t){for(var i=this.length-1;i>=0;i--){if(fn.call(t,this[i],i,this))return i;}return -1;},'findLastIndex');}"
            + "if(!String.prototype.replaceAll){String.prototype.replaceAll=markNative(function(s,r){return s instanceof RegExp?this.replace(s,r):this.split(s).join(r);},'replaceAll');}"
            + "if(window.Element&&!Element.prototype.checkVisibility){Element.prototype.checkVisibility=markNative(function(){var r=this.getBoundingClientRect();return r.width>0&&r.height>0;},'checkVisibility');}"
            + "if(!window.reportError){window.reportError=markNative(function(){},'reportError');}"
            + "if(!window.structuredClone){window.structuredClone=markNative(function(v){return v===undefined?undefined:JSON.parse(JSON.stringify(v));},'structuredClone');}"
            + "if(!window.queueMicrotask){window.queueMicrotask=markNative(function(cb){Promise.resolve().then(cb);},'queueMicrotask');}"
            // 2. Prelude Wasm data: URI fetch fallback (bypasses X connect-src CSP on older WebViews while leaving window.Worker 100% native)
            + "if(window.fetch){"
            + "const origFetch=window.fetch;"
            + "window.fetch=markNative(function(input,init){"
            + "if(typeof input==='string'&&input.indexOf('data:')===0){"
            + "try{"
            + "var comma=input.indexOf(',');"
            + "var meta=input.substring(5,comma);var dataPart=input.substring(comma+1);"
            + "var binStr=(meta.indexOf(';base64')!==-1)?atob(dataPart):decodeURIComponent(dataPart);"
            + "var bytes=new Uint8Array(binStr.length);for(var i=0;i<binStr.length;i++)bytes[i]=binStr.charCodeAt(i);"
            + "var mime=meta.split(';')[0]||'application/octet-stream';"
            + "return Promise.resolve(new Response(bytes.buffer,{status:200,headers:{'Content-Type':mime}}));"
            + "}catch(e){return origFetch.apply(this,arguments);}"
            + "}"
            + "return origFetch.apply(this,arguments);"
            + "},'fetch');"
            + "}"
            // 3. Navigator, window.chrome (exact ['loadTimes','csi','app'] key order for Castle V3 $[179]='cba5f63a') & Performance Memory Profile
            + "if(!window.chrome||!window.chrome.csi){"
            + "var chObj={"
            + "loadTimes:makeNative('loadTimes',function(){return {};}),"
            + "csi:makeNative('csi',function(){return {};}),"
            + "app:{isInstalled:false,InstallState:{DISABLED:'disabled',INSTALLED:'installed',NOT_INSTALLED:'not_installed'},RunningState:{CANNOT_RUN:'cannot_run',READY_TO_RUN:'ready_to_run',RUNNING:'running'}}"
            + "};"
            + "try{Object.defineProperty(window,'chrome',{value:chObj,writable:true,enumerable:true,configurable:true});}catch(e){window.chrome=chObj;}"
            + "}"
            + "if(window.performance&&!('memory' in window.performance)){defProp(window.performance,'memory',function(){return {jsHeapSizeLimit:2147483648,totalJSHeapSize:35651584,usedJSHeapSize:24117248};});}"
            + "const brands=[{brand:'Google Chrome',version:'" + chromeMajor + "'},{brand:'Chromium',version:'" + chromeMajor + "'},{brand:'Not-A.Brand',version:'24'}];"
            + "const fullList=[{brand:'Google Chrome',version:'" + chromeVer + "'},{brand:'Chromium',version:'" + chromeVer + "'},{brand:'Not-A.Brand',version:'24.0.0.0'}];"
            + "const heMap={architecture:'arm',bitness:'64',formFactor:['Mobile'],model:'" + model + "',platformVersion:'" + androidVer + ".0.0',uaFullVersion:'" + chromeVer + "',fullVersionList:fullList,wow64:false};"
            + "const uaData={"
            + "brands:brands,mobile:true,platform:'Android',"
            + "toJSON:markNative(function(){return {brands:brands,mobile:true,platform:'Android'};},'toJSON'),"
            + "getHighEntropyValues:markNative(function(hints){"
            + "var out={brands:brands,mobile:true,platform:'Android'};"
            + "if(Array.isArray(hints)){for(var i=0;i<hints.length;i++){var k=hints[i];if(Object.prototype.hasOwnProperty.call(heMap,k))out[k]=heMap[k];}}"
            + "return Promise.resolve(out);"
            + "},'getHighEntropyValues')"
            + "};"
            + "const connObj={type:'wifi',effectiveType:'4g',rtt:" + connRtt + ",downlink:" + connDownlink + ",saveData:false,onchange:null,ontypechange:null,addEventListener:function(){},removeEventListener:function(){}};"
            + "const limitsProto={"
            + "maxTextureDimension1D:16384,maxTextureDimension2D:16384,maxTextureDimension3D:2048,maxTextureArrayLayers:2048,"
            + "maxBindGroups:4,maxBindGroupsPlusVertexBuffers:24,maxBindingsPerBindGroup:1000,"
            + "maxDynamicUniformBuffersPerPipelineLayout:8,maxDynamicStorageBuffersPerPipelineLayout:4,"
            + "maxSampledTexturesPerShaderStage:16,maxSamplersPerShaderStage:16,maxStorageBuffersPerShaderStage:8,"
            + "maxStorageTexturesPerShaderStage:4,maxUniformBuffersPerShaderStage:12,"
            + "maxUniformBufferBindingSize:65536,maxStorageBufferBindingSize:134217728,"
            + "minUniformBufferOffsetAlignment:256,minStorageBufferOffsetAlignment:256,"
            + "maxVertexBuffers:8,maxBufferSize:268435456,maxVertexAttributes:16,maxVertexBufferArrayStride:2048,"
            + "maxInterStageShaderComponents:60,maxInterStageShaderVariables:16,maxColorAttachments:8,"
            + "maxColorAttachmentBytesPerSample:32,maxComputeWorkgroupStorageSize:16384,"
            + "maxComputeInvocationsPerWorkgroup:256,maxComputeWorkgroupSizeX:256,maxComputeWorkgroupSizeY:256,"
            + "maxComputeWorkgroupSizeZ:64,maxComputeWorkgroupsPerDimension:65535"
            + "};"
            + "const fakeLimits=Object.create(limitsProto);"
            + "const gVendorLower='" + gpuVendor.toLowerCase().split(" ")[0].replace(",", "") + "';"
            + "const fakeAdapter={"
            + "features:new Set(['texture-compression-etc2','texture-compression-astc','shader-f16','depth-clip-control','float32-filterable']),"
            + "limits:fakeLimits,"
            + "info:{vendor:gVendorLower,architecture:'" + gpuArch + "',device:'" + model + "',description:'" + gpuRenderer + "'},"
            + "isFallbackAdapter:false,"
            + "requestAdapterInfo:markNative(function(){return Promise.resolve({vendor:gVendorLower,architecture:'" + gpuArch + "',device:'" + model + "',description:'" + gpuRenderer + "'});},'requestAdapterInfo'),"
            + "requestDevice:markNative(function(){return Promise.resolve({features:new Set(),limits:fakeLimits,queue:{},destroy:function(){}});},'requestDevice')"
            + "};"
            + "const wgslFeatures=new Set(['readonly_and_readwrite_storage_textures','packed_4x8_integer_dot_product','unrestricted_pointer_parameters','pointer_composite_access']);"
            + "const gpuObj={"
            + "requestAdapter:markNative(function(){return Promise.resolve(fakeAdapter);},'requestAdapter'),"
            + "getPreferredCanvasFormat:markNative(function(){return 'rgba8unorm';},'getPreferredCanvasFormat'),"
            + "wgslLanguageFeatures:wgslFeatures"
            + "};"
            + "const storageObj={"
            + "estimate:markNative(function(){return Promise.resolve({quota:" + storageQuota + ",usage:" + storageUsage + ",usageDetails:{indexedDB:18432,caches:" + (storageUsage - 18432) + "}});},'estimate'),"
            + "persisted:markNative(function(){return Promise.resolve(false);},'persisted'),"
            + "persist:markNative(function(){return Promise.resolve(false);},'persist'),"
            + "getDirectory:markNative(function(){return Promise.reject(new DOMException('SecurityError','SecurityError'));},'getDirectory')"
            + "};"
            + "const patchNavigatorProto=function(navProto){"
            + "if(!navProto||patchedSet.has(navProto))return;"
            + "patchedSet.add(navProto);"
            + "defProto(navProto,'webdriver',function(){return false;});"
            + "defProto(navProto,'platform',function(){return '" + platform + "';});"
            + "defProto(navProto,'hardwareConcurrency',function(){return " + cores + ";});"
            + "defProto(navProto,'deviceMemory',function(){return " + ramGb + ";});"
            + "defProto(navProto,'maxTouchPoints',function(){return 5;});"
            + "defProto(navProto,'vendor',function(){return 'Google Inc.';});"
            + "defProto(navProto,'userAgent',function(){return '" + ua + "';});"
            + "defProto(navProto,'appVersion',function(){return '" + ua.replace("Mozilla/", "") + "';});"
            + "defProto(navProto,'pdfViewerEnabled',function(){return false;});"
            + "defProto(navProto,'userAgentData',function(){return uaData;});"
            + "defProto(navProto,'connection',function(){return connObj;});"
            + "defProto(navProto,'gpu',function(){return gpuObj;});"
            + "defProto(navProto,'storage',function(){return storageObj;});"
            + "if(!('mediaCapabilities' in navProto)){defProto(navProto,'mediaCapabilities',function(){return {decodingInfo:markNative(function(){return Promise.resolve({supported:true,smooth:true,powerEfficient:true});},'decodingInfo')};});}"
            + "if('getBattery' in navProto){"
            + "const battObj={charging:false,chargingTime:Infinity,dischargingTime:Infinity,level:" + battLevel + ",addEventListener:function(){},removeEventListener:function(){}};"
            + "navProto.getBattery=markNative(function(){return Promise.resolve(battObj);},'getBattery');"
            + "}"
            + "};"
            + "const np=Navigator.prototype;"
            + "patchNavigatorProto(np);"
            // 4. Screen, Orientation & Display Scale: keep CSS @media (device-width/height/resolution) 100% consistent with screen.width/height & devicePixelRatio (Castle V3 $[604] Kte) and outerHeight <= screen.availHeight (Castle V3 $[446] Rie)
            + "const realSw=(window.screen&&window.screen.width)>0?window.screen.width:360;"
            + "const realSh=(window.screen&&window.screen.height)>0?window.screen.height:" + screenHeight + ";"
            + "const realDpr=(window.devicePixelRatio>0)?window.devicePixelRatio:" + dpr + ";"
            + "defProp(window,'devicePixelRatio',function(){return realDpr;});"
            + "defProp(window,'outerWidth',function(){return window.innerWidth||realSw;});"
            + "defProp(window,'outerHeight',function(){return Math.min(window.innerHeight||realSh,realSh);});"
            + "if(window.Screen){"
            + "const sp=Screen.prototype;"
            + "defProto(sp,'width',function(){return realSw;});"
            + "defProto(sp,'availWidth',function(){return realSw;});"
            + "defProto(sp,'height',function(){return realSh;});"
            + "defProto(sp,'availHeight',function(){return realSh;});"
            + "defProto(sp,'availLeft',function(){return 0;});"
            + "defProto(sp,'availTop',function(){return 0;});"
            + "defProto(sp,'colorDepth',function(){return 24;});"
            + "defProto(sp,'pixelDepth',function(){return 24;});"
            + "const orientObj={type:'portrait-primary',angle:0,onchange:null,addEventListener:function(){},removeEventListener:function(){},lock:markNative(function(){return Promise.resolve();},'lock'),unlock:markNative(function(){},'unlock')};"
            + "defProto(sp,'orientation',function(){return orientObj;});"
            + "}"
            // 4b. CSS Media Query Touchscreen Enforcement: hides Host PC physical mouse/hover attached to LDPlayer
            + "if(window.matchMedia){"
            + "const origMM=window.matchMedia.bind(window);"
            + "window.matchMedia=markNative(function(q){"
            + "const res=origMM(q);"
            + "if(typeof q==='string'){"
            + "var ql=q.toLowerCase().replace(/\\s+/g,'');"
            + "var forceMatch=null;"
            + "if(ql.indexOf('pointer:coarse')!==-1||ql.indexOf('any-pointer:coarse')!==-1||ql.indexOf('hover:none')!==-1||ql.indexOf('any-hover:none')!==-1){forceMatch=true;}"
            + "else if(ql.indexOf('pointer:fine')!==-1||ql.indexOf('any-pointer:fine')!==-1||ql.indexOf('hover:hover')!==-1||ql.indexOf('any-hover:hover')!==-1){forceMatch=false;}"
            + "if(forceMatch!==null){return {matches:forceMatch,media:q,onchange:null,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return true;}};}"
            + "}"
            + "return res;"
            + "},'matchMedia');"
            + "}"
            // 5. Prelude SDK (B8xz5lyW.js) & Castle V3 (IJ) Native PermissionStatus.prototype.state Normalizer, IndexedDB & Font Family ('Roboto:Android')
            + "try{if(window.Notification){defProp(window.Notification,'permission',function(){return 'default';});}}catch(e){}"
            + "if(window.PermissionStatus&&PermissionStatus.prototype){"
            + "const psp=PermissionStatus.prototype;"
            + "const dName=Object.getOwnPropertyDescriptor(psp,'name');"
            + "const dState=Object.getOwnPropertyDescriptor(psp,'state');"
            + "if(dState&&dState.get){"
            + "const grantMap={accelerometer:1,gyroscope:1,magnetometer:1,'background-fetch':1,'background-sync':1,'clipboard-write':1,'payment-handler':1,'screen-wake-lock':1,'storage-access':1};"
            + "defProto(psp,'state',function(){"
            + "var st=dState.get.call(this);"
            + "var nm=dName&&dName.get?dName.get.call(this):'';"
            + "if(grantMap[nm])return 'granted';"
            + "if(st==='denied')return 'prompt';"
            + "return st;"
            + "});"
            + "}"
            + "}"
            + "if(window.IDBFactory&&!IDBFactory.prototype.databases){IDBFactory.prototype.databases=markNative(function(){return Promise.resolve([]);},'databases');}"
            + "if(window.getComputedStyle){"
            + "const origGcs=window.getComputedStyle;"
            + "window.getComputedStyle=markNative(function(el,pseudo){"
            + "const st=origGcs.call(this,el,pseudo);"
            + "if(st&&el&&el.getAttribute){"
            + "const inlineSt=el.getAttribute('style')||'';"
            + "if(inlineSt.indexOf('!important')!==-1&&inlineSt.indexOf('font:')!==-1){"
            + "try{Object.defineProperty(st,'fontFamily',{get:function(){return 'Roboto';},configurable:true});}catch(e){}"
            + "}"
            + "}"
            + "return st;"
            + "},'getComputedStyle');"
            + "}"
            // 6. Deep WebGL 1/2 + OffscreenCanvas + Shader + Blob Worker Metadata Spoofing (Castle V3 Qi/bse/Ms/pte/FJ & Prelude Wasm)
            + "const cSeed=(" + canvasSeed + ")>>>0;"
            + "const aSeed=(" + audioSeed + ")>>>0;"
            + "const mobileExts=['ANGLE_instanced_arrays','EXT_blend_minmax','EXT_color_buffer_float','EXT_color_buffer_half_float','EXT_disjoint_timer_query','EXT_float_blend','EXT_frag_depth','EXT_sRGB','EXT_shader_texture_lod','EXT_texture_filter_anisotropic','OES_EGL_image_external','OES_element_index_uint','OES_fbo_render_mipmap','OES_standard_derivatives','OES_texture_float','OES_texture_float_linear','OES_texture_half_float','OES_texture_half_float_linear','OES_vertex_array_object','WEBGL_color_buffer_float','WEBGL_compressed_texture_astc','WEBGL_compressed_texture_etc','WEBGL_compressed_texture_etc1','WEBGL_debug_renderer_info','WEBGL_debug_shaders','WEBGL_depth_texture','WEBGL_draw_buffers','WEBGL_lose_context','WEBGL_multi_draw'];"
            + "const glslTranslated='#version 100\\nprecision mediump float;\\nvoid main(){\\n(gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0));\\n}\\n';"
            + "const hookWebGL=function(proto,isWgl2){"
            + "if(!proto||patchedSet.has(proto)||!proto.getParameter)return;"
            + "patchedSet.add(proto);"
            + "const origGet=proto.getParameter;"
            + "proto.getParameter=markNative(function(p){"
            + "var code=Number(p);"
            + "if(code===37445)return '" + gpuVendor + "';"
            + "if(code===37446)return '" + gpuRenderer + "';"
            + "if(code===7936)return 'WebKit';"
            + "if(code===7937)return 'WebKit WebGL';"
            + "if(code===7938)return isWgl2?'WebGL 2.0 (OpenGL ES 3.0 Chromium)':'WebGL 1.0 (OpenGL ES 2.0 Chromium)';"
            + "if(code===35724)return isWgl2?'WebGL GLSL ES 3.00 (OpenGL ES GLSL ES 3.0 Chromium)':'WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)';"
            + "if(code===3379||code===34076||code===34024)return 16384;"
            + "if(code===34930||code===35660||code===34921||code===34047)return 16;"
            + "if(code===35661)return 32;"
            + "if(code===36347)return 256;"
            + "if(code===36348)return 31;"
            + "if(code===36349)return 224;"
            + "if(code===3386)return new Int32Array([16384,16384]);"
            + "if(code===33901)return new Float32Array([1,1023]);"
            + "if(code===33902)return new Float32Array([1,1]);"
            + "return origGet.apply(this,arguments);"
            + "},'getParameter');"
            + "if(proto.getSupportedExtensions){"
            + "proto.getSupportedExtensions=markNative(function(){return mobileExts.slice();},'getSupportedExtensions');"
            + "}"
            + "if(proto.getExtension){"
            + "const origExt=proto.getExtension;"
            + "proto.getExtension=markNative(function(name){"
            + "if(typeof name==='string'){"
            + "var nl=name.toLowerCase();"
            + "if(nl.indexOf('s3tc')!==-1||nl.indexOf('bptc')!==-1||nl.indexOf('rgtc')!==-1)return null;"
            + "}"
            + "var res=null;"
            + "try{res=origExt.apply(this,arguments);}catch(e){}"
            + "if(name==='WEBGL_debug_renderer_info'){return res||{UNMASKED_VENDOR_WEBGL:37445,UNMASKED_RENDERER_WEBGL:37446};}"
            + "if(name==='WEBGL_debug_shaders'){"
            + "return {getTranslatedShaderSource:markNative(function(sh){"
            + "try{if(res&&res.getTranslatedShaderSource){var s=res.getTranslatedShaderSource(sh)||'';if(s&&!/register\\(b\\d+\\)|cbuffer|dx_ViewAdjust|HLSL/i.test(s)&&(/#version\\s+\\d/i.test(s)||/gl_Position|gl_FragColor/.test(s)))return s;}}catch(e){}"
            + "return glslTranslated;"
            + "},'getTranslatedShaderSource')};"
            + "}"
            + "return res;"
            + "},'getExtension');"
            + "}"
            + "if(proto.getShaderPrecisionFormat){"
            + "const origPrec=proto.getShaderPrecisionFormat;"
            + "proto.getShaderPrecisionFormat=markNative(function(st,pt){"
            + "try{var r=origPrec.apply(this,arguments);if(r&&r.precision>0)return r;}catch(e){}"
            + "return {rangeMin:127,rangeMax:127,precision:23};"
            + "},'getShaderPrecisionFormat');"
            + "}"
            + "if(proto.readPixels){"
            + "const origRead=proto.readPixels;"
            + "proto.readPixels=markNative(function(x,y,w,h,fmt,type,pixels){"
            + "origRead.apply(this,arguments);"
            + "try{if(pixels&&pixels.length>=16){var idx=((cSeed%4)*4);pixels[idx]=(pixels[idx]+(" + noiseR + "))&0xff;}}catch(e){}"
            + "},'readPixels');"
            + "}"
            + "};"
            + "if(window.WebGLRenderingContext)hookWebGL(WebGLRenderingContext.prototype,false);"
            + "if(window.WebGL2RenderingContext)hookWebGL(WebGL2RenderingContext.prototype,true);"
            + "if(window.HTMLCanvasElement&&HTMLCanvasElement.prototype.getContext){"
            + "const origGetCtx=HTMLCanvasElement.prototype.getContext;"
            + "HTMLCanvasElement.prototype.getContext=markNative(function(t,a){"
            + "const ctx=origGetCtx.apply(this,arguments);"
            + "if(ctx&&typeof t==='string'&&t.indexOf('webgl')!==-1){try{hookWebGL(Object.getPrototypeOf(ctx),t==='webgl2');}catch(e){}}"
            + "return ctx;"
            + "},'getContext');"
            + "}"
            + "if(window.OffscreenCanvas&&OffscreenCanvas.prototype.getContext){"
            + "const origOffCtx=OffscreenCanvas.prototype.getContext;"
            + "OffscreenCanvas.prototype.getContext=markNative(function(t,a){"
            + "const ctx=origOffCtx.apply(this,arguments);"
            + "if(ctx&&typeof t==='string'&&t.indexOf('webgl')!==-1){try{hookWebGL(Object.getPrototypeOf(ctx),t==='webgl2');}catch(e){}}"
            + "return ctx;"
            + "},'getContext');"
            + "}"
            // 6a. Same-Origin Hidden IFrame Realm Synchronizer (Castle V3 hr() -> qX/$[17], mue/$[137], yie/$[466], Wte/$[606])
            + "const patchChildWindow=function(cw){"
            + "try{"
            + "if(!cw||cw===window||patchedSet.has(cw))return;"
            + "patchedSet.add(cw);"
            + "if(cw.Navigator&&cw.Navigator.prototype)patchNavigatorProto(cw.Navigator.prototype);"
            + "if(cw.navigator)patchNavigatorProto(Object.getPrototypeOf(cw.navigator));"
            + "if(cw.WebGLRenderingContext)hookWebGL(cw.WebGLRenderingContext.prototype,false);"
            + "if(cw.WebGL2RenderingContext)hookWebGL(cw.WebGL2RenderingContext.prototype,true);"
            + "}catch(e){}"
            + "};"
            + "if(window.HTMLIFrameElement&&HTMLIFrameElement.prototype){"
            + "const ifp=HTMLIFrameElement.prototype;"
            + "const cwDesc=Object.getOwnPropertyDescriptor(ifp,'contentWindow');"
            + "const cdDesc=Object.getOwnPropertyDescriptor(ifp,'contentDocument');"
            + "if(cwDesc&&cwDesc.get){"
            + "const origCwGet=cwDesc.get;"
            + "defProto(ifp,'contentWindow',function(){const cw=origCwGet.call(this);patchChildWindow(cw);return cw;});"
            + "if(cdDesc&&cdDesc.get){"
            + "const origCdGet=cdDesc.get;"
            + "defProto(ifp,'contentDocument',function(){try{const cw=origCwGet.call(this);patchChildWindow(cw);}catch(e){}return origCdGet.call(this);});"
            + "}"
            + "}"
            + "}"
            + "if(window.Node&&Node.prototype){"
            + "if(Node.prototype.appendChild){"
            + "const origAppend=Node.prototype.appendChild;"
            + "Node.prototype.appendChild=markNative(function(child){"
            + "const res=origAppend.apply(this,arguments);"
            + "try{if(child&&child.tagName==='IFRAME'&&child.contentWindow)patchChildWindow(child.contentWindow);}catch(e){}"
            + "return res;"
            + "},'appendChild');"
            + "}"
            + "if(Node.prototype.insertBefore){"
            + "const origInsert=Node.prototype.insertBefore;"
            + "Node.prototype.insertBefore=markNative(function(child,ref){"
            + "const res=origInsert.apply(this,arguments);"
            + "try{if(child&&child.tagName==='IFRAME'&&child.contentWindow)patchChildWindow(child.contentWindow);}catch(e){}"
            + "return res;"
            + "},'insertBefore');"
            + "}"
            + "}"
            // 6b. Blob Web Worker WebGL + WorkerNavigator Metadata Injector (Castle V3 FJ/Pde/pte/WY/Gre/Yne & RJ SharedWorker 100% parity)
            + "if(window.Blob){"
            + "const OrigBlob=window.Blob;"
            + "const workerPreamble='(function(){try{'"
            + "+'var nav=self.navigator;if(nav){var np=Object.getPrototypeOf(nav)||nav;'"
            + "+'var def=function(k,v){try{Object.defineProperty(np,k,{get:function(){return v;},configurable:true,enumerable:true});}catch(e){}};"
            + "def(\"userAgent\",\"" + ua + "\");"
            + "def(\"platform\",\"" + platform + "\");"
            + "def(\"hardwareConcurrency\"," + cores + ");"
            + "def(\"deviceMemory\"," + ramGb + ");"
            + "var br=[{brand:\"Google Chrome\",version:\"" + chromeMajor + "\"},{brand:\"Chromium\",version:\"" + chromeMajor + "\"},{brand:\"Not-A.Brand\",version:\"24\"}];"
            + "var fl=[{brand:\"Google Chrome\",version:\"" + chromeVer + "\"},{brand:\"Chromium\",version:\"" + chromeVer + "\"},{brand:\"Not-A.Brand\",version:\"24.0.0.0\"}];"
            + "var hm={architecture:\"arm\",bitness:\"64\",formFactor:[\"Mobile\"],model:\"" + model + "\",platformVersion:\"" + androidVer + ".0.0\",uaFullVersion:\"" + chromeVer + "\",fullVersionList:fl,wow64:false};"
            + "def(\"userAgentData\",{brands:br,mobile:true,platform:\"Android\",toJSON:function(){return {brands:br,mobile:true,platform:\"Android\"};},getHighEntropyValues:function(h){var o={brands:br,mobile:true,platform:\"Android\"};if(Array.isArray(h)){for(var i=0;i<h.length;i++){var k=h[i];if(Object.prototype.hasOwnProperty.call(hm,k))o[k]=hm[k];}}return Promise.resolve(o);}});"
            + "}'"
            + "+'var hw=function(p){if(!p||p.__xw||!p.getParameter)return;p.__xw=true;var og=p.getParameter;p.getParameter=function(q){var c=Number(q);if(c===37445)return \"" + gpuVendor + "\";if(c===37446)return \"" + gpuRenderer + "\";if(c===7936)return \"WebKit\";if(c===7937)return \"WebKit WebGL\";return og.apply(this,arguments);};if(p.getExtension){var oe=p.getExtension;p.getExtension=function(n){var r=null;try{r=oe.apply(this,arguments);}catch(e){}if(n===\"WEBGL_debug_renderer_info\")return r||{UNMASKED_VENDOR_WEBGL:37445,UNMASKED_RENDERER_WEBGL:37446};return r;};}};"
            + "if(self.WebGLRenderingContext)hw(self.WebGLRenderingContext.prototype);"
            + "if(self.WebGL2RenderingContext)hw(self.WebGL2RenderingContext.prototype);"
            + "if(self.OffscreenCanvas&&self.OffscreenCanvas.prototype.getContext){var oc=self.OffscreenCanvas.prototype.getContext;self.OffscreenCanvas.prototype.getContext=function(t,a){var x=oc.apply(this,arguments);if(x&&typeof t===\"string\"&&t.indexOf(\"webgl\")!==-1){try{hw(Object.getPrototypeOf(x));}catch(e){}}return x;};}'"
            + "+'}catch(e){}})();\\n';"
            + "const WrappedBlob=function Blob(parts,opts){"
            + "try{"
            + "var isJs=(opts&&typeof opts.type==='string'&&/javascript|ecmascript/i.test(opts.type));"
            + "if(!isJs&&Array.isArray(parts)&&parts.length>0&&typeof parts[0]==='string'&&(parts[0].indexOf('onmessage')!==-1||parts[0].indexOf('OffscreenCanvas')!==-1||parts[0].indexOf('WEBGL')!==-1)){isJs=true;}"
            + "if(isJs&&Array.isArray(parts)){"
            + "var patched=[workerPreamble].concat(parts);"
            + "return opts!==undefined?new OrigBlob(patched,opts):new OrigBlob(patched);"
            + "}"
            + "}catch(e){}"
            + "return opts!==undefined?new OrigBlob(parts,opts):new OrigBlob(parts);"
            + "};"
            + "nativeMap.set(WrappedBlob,'function Blob() { [native code] }');"
            + "WrappedBlob.prototype=OrigBlob.prototype;"
            + "try{Object.defineProperty(OrigBlob.prototype,'constructor',{value:WrappedBlob,writable:true,configurable:true});}catch(e){}"
            + "window.Blob=WrappedBlob;"
            + "}"
            // 7. Castle V3 Async Probes: MediaDevices, SpeechVoices, and Non-Destructive WebRTC ICE Scrubber ($[209], $[212], $[433]='dtls-sctp+udp')
            + "const fakeDevices=["
            + "{deviceId:'default',kind:'audioinput',label:'',groupId:'" + mediaGroupId + "',toJSON:function(){return {deviceId:this.deviceId,kind:this.kind,label:this.label,groupId:this.groupId};}},"
            + "{deviceId:'" + micId + "',kind:'audioinput',label:'',groupId:'" + mediaGroupId + "',toJSON:function(){return {deviceId:this.deviceId,kind:this.kind,label:this.label,groupId:this.groupId};}},"
            + "{deviceId:'" + camFrontId + "',kind:'videoinput',label:'',groupId:'" + mediaGroupId + "',toJSON:function(){return {deviceId:this.deviceId,kind:this.kind,label:this.label,groupId:this.groupId};}},"
            + "{deviceId:'" + camBackId + "',kind:'videoinput',label:'',groupId:'" + mediaGroupId + "',toJSON:function(){return {deviceId:this.deviceId,kind:this.kind,label:this.label,groupId:this.groupId};}}"
            + "];"
            + "if(navigator.mediaDevices){"
            + "navigator.mediaDevices.enumerateDevices=markNative(function(){return Promise.resolve(fakeDevices);},'enumerateDevices');"
            + "}else{"
            + "defProto(np,'mediaDevices',function(){return {enumerateDevices:markNative(function(){return Promise.resolve(fakeDevices);},'enumerateDevices'),addEventListener:function(){},removeEventListener:function(){}};});"
            + "}"
            + "const fakeVoices=["
            + "{voiceURI:'en-us-x-sfg-network',name:'English United States',lang:'en-US',localService:false,default:true},"
            + "{voiceURI:'en-us-x-sfg-local',name:'English United States',lang:'en-US',localService:true,default:false},"
            + "{voiceURI:'en-gb-x-gba-local',name:'English United Kingdom',lang:'en-GB',localService:true,default:false}"
            + "];"
            + "if(window.speechSynthesis){"
            + "window.speechSynthesis.getVoices=markNative(function(){return fakeVoices;},'getVoices');"
            + "}else{"
            + "defProp(window,'speechSynthesis',function(){return {getVoices:markNative(function(){return fakeVoices;},'getVoices'),speaking:false,pending:false,paused:false,onvoiceschanged:null,addEventListener:function(){},removeEventListener:function(){}};});"
            + "}"
            // WebRTC Non-Destructive Emulator Private-IP Scrubber on RTCIceCandidate.prototype: keeps RTCPeerConnection 100% native while scrubbing 10.0.2.x -> lanIp for Castle V3 jJ()
            + "const lanIp='" + lanIp + "';"
            + "const pubIp='" + clientIp + "';"
            + "const scrubIpStr=function(str){"
            + "if(typeof str!=='string')return str;"
            + "var out=str.replace(/10\\.0\\.2\\.\\d+/g,lanIp).replace(/172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+/g,lanIp);"
            + "if(pubIp&&pubIp.length>6){"
            + "out=out.replace(/(typ\\s+srflx\\s+raddr\\s+)([0-9.]+)/g,'$1'+lanIp);"
            + "out=out.replace(/(candidate:\\S+\\s+\\d+\\s+\\S+\\s+\\d+\\s+)([0-9.]+)(\\s+\\d+\\s+typ\\s+srflx)/g,'$1'+pubIp+'$3');"
            + "}"
            + "return out;"
            + "};"
            + "if(window.RTCIceCandidate&&RTCIceCandidate.prototype){"
            + "const icp=RTCIceCandidate.prototype;"
            + "const dCand=Object.getOwnPropertyDescriptor(icp,'candidate');"
            + "const dAddr=Object.getOwnPropertyDescriptor(icp,'address');"
            + "const dRel=Object.getOwnPropertyDescriptor(icp,'relatedAddress');"
            + "const dType=Object.getOwnPropertyDescriptor(icp,'type');"
            + "if(dCand&&dCand.get){defProto(icp,'candidate',function(){var v=dCand.get.call(this);return typeof v==='string'?scrubIpStr(v):v;});}"
            + "if(dAddr&&dAddr.get){defProto(icp,'address',function(){var v=dAddr.get.call(this);var t=dType&&dType.get?dType.get.call(this):null;if(pubIp&&pubIp.length>6&&t==='srflx')return pubIp;return typeof v==='string'?scrubIpStr(v):v;});}"
            + "if(dRel&&dRel.get){defProto(icp,'relatedAddress',function(){var v=dRel.get.call(this);return typeof v==='string'?scrubIpStr(v):v;});}"
            + "}"
            // 7b. Native Sensor Prototype Getter Normalizer (never calls dispatchEvent with synthetic isTrusted=false events which trigger Castle V3 Md/Pee untrusted-event trap!)
            + "var s0=(cSeed&0xff)/255.0;"
            + "if(window.DeviceOrientationEvent&&DeviceOrientationEvent.prototype){"
            + "const dop=DeviceOrientationEvent.prototype;"
            + "const dAlpha=Object.getOwnPropertyDescriptor(dop,'alpha');"
            + "const dBeta=Object.getOwnPropertyDescriptor(dop,'beta');"
            + "const dGamma=Object.getOwnPropertyDescriptor(dop,'gamma');"
            + "if(dAlpha&&dAlpha.get){defProto(dop,'alpha',function(){var v=dAlpha.get.call(this);return (v===0)?(142.4+s0*12.0):v;});}"
            + "if(dBeta&&dBeta.get){defProto(dop,'beta',function(){var v=dBeta.get.call(this);return (v===0)?(38.2+s0*4.5):v;});}"
            + "if(dGamma&&dGamma.get){defProto(dop,'gamma',function(){var v=dGamma.get.call(this);return (v===0)?(-2.1+s0*1.8):v;});}"
            + "}"
            // 8. Deterministic 32-bit Seeded Sub-Pixel Glyph Rasterization on BOTH HTMLCanvasElement & OffscreenCanvas (modifies [34] & [463] canvas hashes natively in Skia while keeping $[240]=true, $[427]=0 & $[570]=0)
            + "const subDx=((cSeed&0xff)+17)*0.0015;"
            + "const subDy=(((cSeed>>>8)&0xff)+11)*0.0015;"
            + "const hookFillText=function(proto){"
            + "if(!proto||!proto.fillText||patchedSet.has(proto))return;"
            + "patchedSet.add(proto);"
            + "const origFillText=proto.fillText;"
            + "proto.fillText=markNative(function(text,x,y,maxW){"
            + "if(typeof x==='number'&&typeof y==='number'){"
            + "return arguments.length>3?origFillText.call(this,text,x+subDx,y+subDy,maxW):origFillText.call(this,text,x+subDx,y+subDy);"
            + "}"
            + "return origFillText.apply(this,arguments);"
            + "},'fillText');"
            + "};"
            + "if(window.CanvasRenderingContext2D)hookFillText(CanvasRenderingContext2D.prototype);"
            + "if(window.OffscreenCanvasRenderingContext2D)hookFillText(OffscreenCanvasRenderingContext2D.prototype);"
            + "if(window.AudioBuffer){"
            + "const origCh=AudioBuffer.prototype.getChannelData;"
            + "AudioBuffer.prototype.getChannelData=markNative(function(c){"
            + "const d=origCh.call(this,c);"
            + "if(d&&d.length>10&&!patchedSet.has(d)){"
            + "patchedSet.add(d);var s=(aSeed^(c+1))>>>0;"
            + "for(var i=0;i<d.length;i+=32){s=(Math.imul(s,1664525)+1013904223)>>>0;d[i]+=((s&0xff)-128)*1e-8;}"
            + "}"
            + "return d;"
            + "},'getChannelData');"
            + "}"
            + "if(window.AnalyserNode&&AnalyserNode.prototype.getFloatFrequencyData){"
            + "const origFreq=AnalyserNode.prototype.getFloatFrequencyData;"
            + "AnalyserNode.prototype.getFloatFrequencyData=markNative(function(arr){"
            + "origFreq.call(this,arr);"
            + "if(arr&&arr.length>8){var s=aSeed;for(var i=0;i<arr.length;i+=16){s=(Math.imul(s,1664525)+1013904223)>>>0;arr[i]+=((s&0xff)-128)*1e-6;}}"
            + "},'getFloatFrequencyData');"
            + "}"
            + "}catch(err){}"
            + "})();";
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
