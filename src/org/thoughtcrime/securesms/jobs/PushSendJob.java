package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.util.Log;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.jobs.requirements.MasterSecretRequirement;
import org.thoughtcrime.securesms.mms.PartAuthority;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.Recipients;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.textsecure.api.messages.TextSecureAttachment;
import org.whispersystems.textsecure.api.messages.TextSecureAttachmentStream;
import org.thoughtcrime.securesms.database.TextSecureDirectory;
import org.whispersystems.textsecure.api.push.PushAddress;
import org.whispersystems.textsecure.api.util.InvalidNumberException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;

import ws.com.google.android.mms.ContentType;
import ws.com.google.android.mms.pdu.PduPart;
import ws.com.google.android.mms.pdu.SendReq;

public abstract class PushSendJob extends MasterSecretJob {

  private static final String TAG = PushSendJob.class.getSimpleName();

  protected PushSendJob(Context context, JobParameters parameters) {
    super(context, parameters);
  }

  protected static JobParameters constructParameters(Context context, String destination, boolean media) {
    JobParameters.Builder builder = JobParameters.newBuilder();
    builder.withPersistence();
    builder.withGroupId(destination);
    builder.withRequirement(new MasterSecretRequirement(context));

    if (!isSmsFallbackSupported(context, destination, media)) {
      builder.withRequirement(new NetworkRequirement(context));
      builder.withRetryCount(5);
    }

    return builder.create();
  }

  protected static boolean isSmsFallbackSupported(Context context, String destination, boolean media) {
    try {
      String e164number = Util.canonicalizeNumber(context, destination);

      if (GroupUtil.isEncodedGroup(e164number)) {
        return false;
      }

      if (!TextSecurePreferences.isFallbackSmsAllowed(context)) {
        return false;
      }

      if (media && !TextSecurePreferences.isFallbackMmsEnabled(context)) {
        return false;
      }

      TextSecureDirectory directory = TextSecureDirectory.getInstance(context);
      return directory.isSmsFallbackSupported(e164number);
    } catch (InvalidNumberException e) {
      Log.w(TAG, e);
      return false;
    }
  }

  protected PushAddress getPushAddress(Recipient recipient) throws InvalidNumberException {
    String e164number = Util.canonicalizeNumber(context, recipient.getNumber());
    String relay      = TextSecureDirectory.getInstance(context).getRelay(e164number);
    return new PushAddress(recipient.getRecipientId(), e164number, 1, relay);
  }

  protected boolean isSmsFallbackApprovalRequired(String destination, boolean media) {
    return (isSmsFallbackSupported(context, destination, media) && TextSecurePreferences.isFallbackSmsAskRequired(context));
  }

  protected List<TextSecureAttachment> getAttachments(final MasterSecret masterSecret, final SendReq message) {
    List<TextSecureAttachment> attachments = new LinkedList<>();

    for (int i=0;i<message.getBody().getPartsNum();i++) {
      PduPart part = message.getBody().getPart(i);
      String contentType = Util.toIsoString(part.getContentType());
      if (ContentType.isImageType(contentType) ||
          ContentType.isAudioType(contentType) ||
          ContentType.isVideoType(contentType))
      {

        try {
          InputStream is = PartAuthority.getPartStream(context, masterSecret, part.getDataUri());
          attachments.add(new TextSecureAttachmentStream(is, contentType, part.getDataSize()));
        } catch (IOException ioe) {
          Log.w(TAG, "Couldn't open attachment", ioe);
        }
      }
    }

    return attachments;
  }

  protected void notifyMediaMessageDeliveryFailed(Context context, long messageId) {
    long       threadId   = DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    Recipients recipients = DatabaseFactory.getThreadDatabase(context).getRecipientsForThreadId(threadId);

    MessageNotifier.notifyMessageDeliveryFailed(context, recipients, threadId);
  }
}
