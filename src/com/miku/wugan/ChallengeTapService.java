package com.miku.wugan;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Locale;

/**
 * 自动点选 Cloudflare Turnstile 复选框的无障碍服务。
 * 只在检测到验证页面、且找到"复选框"候选节点时才点一次（15 秒冷却），
 * 不做任何 JS 注入，不碰页面内容。
 */
public class ChallengeTapService extends AccessibilityService {

    private static final String TAG = "WuganTap";
    private static final String PKG = "com.miku.wugan";
    private static final long COOLDOWN_MS = 15_000;

    // 出现在页面里即判定为"验证页"（含内嵌式 Turnstile 挂件，如 dash.cloudflare.com 注册页）
    private static final String[] CHALLENGE_HINTS = {
            "verifying you are human",
            "verify you are human",
            "just a moment",
            "确认您是真人",
            "验证您是人类",
            "请验证",
            "请验证您是真人",
            "验证您是真人",
            "我是真人",
            "我不是机器人",
            "i'm not a robot",
            "i am not a robot",
            "prove you are human",
            "turnstile",
            "请稍候",
            "cloudflare"
    };

    // 可点的复选框候选（文本/描述命中其一即可）
    private static final String[] CHECKBOX_HINTS = {
            "verify you are human",
            "verifying you are human",
            "确认您是真人",
            "请验证您是真人",
            "我是人类",
            "我是真人",
            "我不是机器人",
            "i am human",
            "i'm human",
            "i'm not a robot",
            "i am not a robot",
            "人机验证"
    };

    private long lastTapMs = 0;
    private int lastWindowId = -1;

    @Override
    public void onServiceConnected() {
        Log.i(TAG, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !PKG.contentEquals(pkg)) {
            return; // 只处理本应用
        }
        int wid = event.getWindowId();
        if (wid != lastWindowId) {
            lastWindowId = wid;
            lastTapMs = 0; // 切窗口，重置冷却
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return;
        }
        try {
            boolean challenge = containsChallenge(root);
            if (challenge && tapCooldownOk()) {
                tapCheckbox(root, challenge);
            }
        } finally {
            root.recycle();
        }
    }

    private boolean tapCooldownOk() {
        return SystemClock.uptimeMillis() - lastTapMs >= COOLDOWN_MS;
    }

    /** 整棵树里有没有验证页的痕迹 */
    private boolean containsChallenge(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        String hay = nodeText(node);
        if (hay != null) {
            String lower = hay.toLowerCase(Locale.ROOT);
            for (String h : CHALLENGE_HINTS) {
                if (lower.contains(h)) {
                    return true;
                }
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            boolean found = containsChallenge(child);
            if (child != null) {
                child.recycle();
            }
            if (found) {
                return true;
            }
        }
        return false;
    }

    /** 找到复选框候选就点，点完返回 true */
    private boolean tapCheckbox(AccessibilityNodeInfo node, boolean challengeDetected) {
        if (node == null) {
            return false;
        }
        if (node.isClickable() && isCheckboxCandidate(node, challengeDetected)) {
            lastTapMs = SystemClock.uptimeMillis();
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "auto-tap checkbox, result=" + ok);
            return true;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            boolean done = tapCheckbox(child, challengeDetected);
            if (child != null) {
                child.recycle();
            }
            if (done) {
                return true;
            }
        }
        return false;
    }

    /**
     * 可点节点判定（宽松版）：
     * (a) 文本/无障碍描述命中复选框关键词；或
     * (b) 类名含 CheckBox 且当前屏已判定为验证页。
     */
    private boolean isCheckboxCandidate(AccessibilityNodeInfo node, boolean challengeDetected) {
        String hay = nodeText(node);
        if (hay != null) {
            String lower = hay.toLowerCase(Locale.ROOT);
            for (String h : CHECKBOX_HINTS) {
                if (lower.contains(h)) {
                    return true;
                }
            }
        }
        if (challengeDetected) {
            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().contains("CheckBox")) {
                return true;
            }
        }
        return false;
    }

    /** 节点文本 + 无障碍描述拼在一起查 */
    private static String nodeText(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text == null && desc == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (text != null) {
            sb.append(text);
        }
        if (desc != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(desc);
        }
        return sb.toString();
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "service interrupted");
    }
}
