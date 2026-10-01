package com.miku.wugan;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    /** 诊断用心跳：服务每次成功存活都写一条，设置页能看到 */
    static final String A11Y_PREFS = "wugan_a11y";
    static final String KEY_LAST_CONNECT = "last_connect";
    static final String KEY_LAST_UNBIND = "last_unbind";
    /** v10.2 遥测：最后一次看到验证挂件 / 最后一次点选时间与结果 */
    static final String KEY_LAST_WIDGET_SEEN = "last_widget_seen";
    static final String KEY_LAST_TAP_TIME = "last_tap_time";
    static final String KEY_LAST_TAP_OK = "last_tap_ok";

    /**
     * v10.2 新路径（加法）：验证挂件的标签文字。
     * 不问"整页是不是验证页"，标签 + 复选框成对出现才算遇到验证。
     */
    private static final String[] WIDGET_LABEL_HINTS = {
            "请验证您是真人",
            "验证您是真人",
            "verify you are human",
            "verifying you are human",
            "我不是机器人",
            "i'm not a robot",
            "i am not a robot",
            "确认您是真人"
    };

    static String formatTime(long ms) {
        if (ms <= 0) {
            return null;
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(ms));
    }

    @Override
    public void onServiceConnected() {
        Log.i(TAG, "service connected");
        getSharedPreferences(A11Y_PREFS, MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_CONNECT, System.currentTimeMillis())
                .apply();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Log.i(TAG, "service unbound");
        getSharedPreferences(A11Y_PREFS, MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_UNBIND, System.currentTimeMillis())
                .apply();
        return super.onUnbind(intent);
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
            boolean tapped = false;
            if (challenge && tapCooldownOk()) {
                tapped = tapCheckbox(root, challenge);
            }
            // v10.2 新路径（加法）：老路径没点中时，再试挂件级检测。
            // 老代码一行未动，整页验证的效果不受影响。
            if (!tapped && tapCooldownOk()) {
                tapWidgetCheckbox(root);
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

    /** 找到复选框候选就点，点完返回 true（先深后浅：优先点最里层的可点节点） */
    private boolean tapCheckbox(AccessibilityNodeInfo node, boolean challengeDetected) {
        if (node == null) {
            return false;
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
        if (node.isClickable() && isCheckboxCandidate(node, challengeDetected)) {
            lastTapMs = SystemClock.uptimeMillis();
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "auto-tap checkbox, result=" + ok);
            return true;
        }
        return false;
    }

    /**
     * v10.2 新路径（加法）：挂件级检测。
     * 先找命中验证标签的节点，再从每个标签的祖先由近到远找复选框——
     * 离标签最近、且含"可点未选中复选框"的那一层祖先就是挂件容器，
     * 只在容器里挑最优的点，容器外的复选框（如"订阅邮件"）碰不到。
     */
    private boolean tapWidgetCheckbox(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        try {
            collectLabelNodes(root, labels);
            if (!labels.isEmpty()) {
                // 遥测：看到挂件了（即使冷却中没点，也记下来，诊断框能看到）
                getSharedPreferences(A11Y_PREFS, MODE_PRIVATE).edit()
                        .putLong(KEY_LAST_WIDGET_SEEN, System.currentTimeMillis())
                        .apply();
            }
            for (AccessibilityNodeInfo label : labels) {
                if (tapNearestWidget(label)) {
                    return true;
                }
            }
        } finally {
            for (AccessibilityNodeInfo l : labels) {
                l.recycle();
            }
        }
        return false;
    }

    /** 收集文本/无障碍描述命中挂件标签的节点（独立拷贝，调用方回收） */
    private void collectLabelNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) {
            return;
        }
        String hay = nodeText(node);
        if (hay != null && containsAnyLower(hay, WIDGET_LABEL_HINTS)) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            collectLabelNodes(c, out);
            if (c != null) {
                c.recycle();
            }
        }
    }

    /** 从标签节点往上，由近到远找第一个含可点未选中复选框的祖先层，点最优的 */
    private boolean tapNearestWidget(AccessibilityNodeInfo label) {
        List<AccessibilityNodeInfo> chain = new ArrayList<>();
        AccessibilityNodeInfo p = label.getParent();
        try {
            for (int up = 0; up < 4 && p != null; up++) {
                AccessibilityNodeInfo next = p.getParent();
                chain.add(p);
                p = next;
            }
            if (p != null) {
                p.recycle();
            }
            for (AccessibilityNodeInfo anc : chain) {
                Cand best = findBestCheckbox(anc);
                if (best.node != null) {
                    lastTapMs = SystemClock.uptimeMillis();
                    boolean ok = best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "widget auto-tap, score=" + best.score + " result=" + ok);
                    getSharedPreferences(A11Y_PREFS, MODE_PRIVATE).edit()
                            .putLong(KEY_LAST_TAP_TIME, System.currentTimeMillis())
                            .putBoolean(KEY_LAST_TAP_OK, ok)
                            .apply();
                    best.node.recycle();
                    return true;
                }
            }
        } finally {
            for (AccessibilityNodeInfo a : chain) {
                a.recycle();
            }
        }
        return false;
    }

    /** 候选节点（独立拷贝，调用方负责 recycle） */
    private static class Cand {
        AccessibilityNodeInfo node;
        int score;
    }

    /**
     * 在祖先子树里找最优复选框（先深后浅，同分取最深的）。
     * 评分：3=类名是 CheckBox 的可点未选中框；2=自身文本就是验证标签的可点节点；
     * 1=子树含验证标签的可点 wrapper。已选中的一律不要（避免把已勾选的框点掉）。
     */
    private Cand findBestCheckbox(AccessibilityNodeInfo scope) {
        Cand best = new Cand();
        findBestInto(scope, best);
        return best;
    }

    private void findBestInto(AccessibilityNodeInfo node, Cand best) {
        if (node == null || best.score >= 3) {
            return;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            findBestInto(c, best);
            if (c != null) {
                c.recycle();
            }
            if (best.score >= 3) {
                return;
            }
        }
        int s = scoreCheckboxNode(node);
        if (s > best.score) {
            if (best.node != null) {
                best.node.recycle();
            }
            best.node = AccessibilityNodeInfo.obtain(node);
            best.score = s;
        }
    }

    private int scoreCheckboxNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isClickable() || node.isChecked()) {
            return 0;
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("CheckBox")) {
            return 3;
        }
        String hay = nodeText(node);
        if (hay != null && containsAnyLower(hay, WIDGET_LABEL_HINTS)) {
            return 2;
        }
        if (subtreeHasHint(node, 3, WIDGET_LABEL_HINTS)) {
            return 1;
        }
        return 0;
    }

    /**
     * 可点节点判定：
     * (a) 自身文本/无障碍描述命中复选框关键词；或
     * (b) 可点节点子树里含复选框关键词（Turnstile 内嵌挂件把"请验证您是真人"放在
     *     可点 wrapper 的子 span 上，自己身上没文本，之前永远点不到）；或
     * (c) 类名含 CheckBox 且当前屏已判定为验证页。
     */
    private boolean isCheckboxCandidate(AccessibilityNodeInfo node, boolean challengeDetected) {
        String hay = nodeText(node);
        if (hay != null && containsAnyLower(hay, CHECKBOX_HINTS)) {
            return true;
        }
        if (node.isClickable() && subtreeHasHint(node, 4)) {
            return true;
        }
        if (challengeDetected) {
            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().contains("CheckBox")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAnyLower(String hay, String[] hints) {
        String lower = hay.toLowerCase(Locale.ROOT);
        for (String h : hints) {
            if (lower.contains(h)) {
                return true;
            }
        }
        return false;
    }

    /** 子树（限深）里有没有复选框关键词（老路径用） */
    private boolean subtreeHasHint(AccessibilityNodeInfo node, int depth) {
        return subtreeHasHint(node, depth, CHECKBOX_HINTS);
    }

    /** 子树（限深）里有没有指定关键词（v10.2 新路径用挂件标签词） */
    private boolean subtreeHasHint(AccessibilityNodeInfo node, int depth, String[] hints) {
        if (node == null || depth < 0) {
            return false;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            boolean found = false;
            if (child != null) {
                String hay = nodeText(child);
                if (hay != null && containsAnyLower(hay, hints)) {
                    found = true;
                } else {
                    found = subtreeHasHint(child, depth - 1, hints);
                }
                child.recycle();
            }
            if (found) {
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
