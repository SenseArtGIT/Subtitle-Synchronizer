package com.senseart.subsync

import android.service.notification.NotificationListenerService

/**
 * Не прави нищо с известията. Нужен е само защото Android позволява
 * четене на позицията на плейъра (MediaSession) на приложения
 * с „Достъп до известия“.
 */
class MediaListenerService : NotificationListenerService()
