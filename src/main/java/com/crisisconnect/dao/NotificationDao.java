package com.crisisconnect.dao;

import com.crisisconnect.model.Notification;
import com.crisisconnect.model.NotificationChannel;

import java.util.List;

public interface NotificationDao {

    Notification save(Notification n);

    /** Newest first; channel may be null for "all channels". */
    List<Notification> findRecent(NotificationChannel channel, int limit);

    List<Notification> findByRequest(int requestId);

    int count();
}
