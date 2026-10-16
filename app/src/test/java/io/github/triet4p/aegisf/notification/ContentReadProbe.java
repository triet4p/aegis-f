package io.github.triet4p.aegisf.notification;

import android.app.Notification;
import android.os.Process;
import android.service.notification.StatusBarNotification;

/**
 * A real posted notification that reports whether its content was read after it was built.
 *
 * The first guarantee of the intake boundary is that a notification from a package outside
 * the user's allowlist is dropped without its content being touched. A platform
 * {@code StatusBarNotification} exposes no way to observe that, but its content is reached
 * only through {@link #getNotification()}, so this probe counts those calls.
 *
 * The count deliberately starts after construction: the platform class itself asks for the
 * notification while it is being built (to derive its group key), and that read belongs to
 * construction, not to any intake decision. Everything counted afterwards was requested by
 * the code under test.
 */
class ContentReadProbe extends StatusBarNotification {

    private int contentReads;
    private final int constructionReads;

    @SuppressWarnings("deprecation")
    ContentReadProbe(String packageName, int id, Notification content) {
        super(
                packageName,
                packageName,
                id,
                null,
                Process.myUid(),
                0,
                0,
                content,
                Process.myUserHandle(),
                System.currentTimeMillis());
        constructionReads = contentReads;
    }

    /** How many times this notification's content was requested after construction. */
    int contentReads() {
        return contentReads - constructionReads;
    }

    @Override
    public Notification getNotification() {
        contentReads++;
        return super.getNotification();
    }
}
