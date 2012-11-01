package jenkins.plugins.hipchat;

import hudson.Util;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.CauseAction;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.logging.Logger;
import java.io.IOException;

import org.apache.commons.lang.StringUtils;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

   private static final Logger logger = Logger.getLogger(HipChatListener.class.getName());

   HipChatNotifier notifier;

   public ActiveNotifier(HipChatNotifier notifier) {
      super();
      this.notifier = notifier;
   }

   private HipChatService getHipChat(AbstractBuild r) {
      AbstractProject<?, ?> project = r.getProject();
      String projectRoom = Util.fixEmpty(project.getProperty(HipChatNotifier.HipChatJobProperty.class).getRoom());
      return notifier.newHipChatService(projectRoom);
   }

   public void deleted(AbstractBuild r) {}

   public void started(AbstractBuild build) {
      String changes = getChanges(build);
      CauseAction cause = build.getAction(CauseAction.class);
      if(changes != null) {
         notifyStart(build, changes);
      }
      else if(cause != null) {
         MessageBuilder message = new MessageBuilder(notifier, build);
         message.append(cause.getShortDescription());
         notifyStart(build, message.appendOpenLink().toString());
      }
      else {
         notifyStart(build, getBuildStatusMessage(build));
      }
   }

   private void notifyStart(AbstractBuild build, String message) {
      getHipChat(build).publish(message, "green");
   }

    public void finalized(AbstractBuild r) {}

   public void completed(AbstractBuild r) {
      getHipChat(r).publish(getBuildStatusMessage(r), getBuildColor(r));
   }

   String getChanges(AbstractBuild r) {
      if(!r.hasChangeSetComputed()) {
         logger.info("No change set computed...");
         return null;
      }
      ChangeLogSet changeSet = r.getChangeSet();
      if(changeSet.isEmptySet()) {
         logger.info("Empty change...");
         return null;
      }
      List<Entry> entries = new LinkedList<Entry>();
      Set<AffectedFile> files = new HashSet<AffectedFile>();
      for(Object o : changeSet.getItems()) {
         Entry entry = (Entry)o;
         logger.info("Entry " + o);
         entries.add(entry);
         files.addAll(entry.getAffectedFiles());
      }
      MessageBuilder message = new MessageBuilder(notifier, r);
      message.append("Started by changes from ");
      message.appendCommitInfo();
      message.append(" (");
      message.append(files.size());
      message.append(" file(s) changed)");
      return message.appendOpenLink().toString();
   }

   static String getBuildColor(AbstractBuild r) {
      Result result = r.getResult();
      if(result == Result.SUCCESS) {
         return "green";
      }
      else if(result == Result.FAILURE) {
         return "red";
      }
      else {
         return "yellow";
      }
   }

   String getBuildStatusMessage(AbstractBuild r) {
      MessageBuilder message = new MessageBuilder(notifier, r);
      message.appendStatusMessage();
      message.append(" for ");
      message.appendCommitInfo();
      message.append(" - ");
      message.appendDuration();
      return message.appendOpenLink().toString();
   }

   public static class MessageBuilder {
      private StringBuffer message;
      private HipChatNotifier notifier;
      private AbstractBuild build;

      public MessageBuilder(HipChatNotifier notifier, AbstractBuild build) {
         this.notifier = notifier;
         this.message = new StringBuffer();
         this.build = build;
         startMessage();
      }

      public MessageBuilder appendStatusMessage() {
         message.append(getStatusMessage(build));
         return this;
      }

      static String getStatusMessage(AbstractBuild r) {
         if(r.isBuilding()) {
            return "Starting...";
         }
         Result result = r.getResult();
         if(result == Result.SUCCESS) return "Success";
         if(result == Result.FAILURE) return "<b>FAILURE</b>";
         if(result == Result.ABORTED) return "ABORTED";
         if(result == Result.NOT_BUILT) return "Not built";
         if(result == Result.UNSTABLE) return "Unstable";
         return "Unknown";
      }

      public MessageBuilder append(String string) {
         message.append(string);
         return this;
      }

      public MessageBuilder append(Object string) {
         message.append(string.toString());
         return this;
      }

      private MessageBuilder startMessage() {
         message.append(build.getProject().getDisplayName());
         message.append(" -");
         this.appendCommitLink();
         message.append(" ");
         return this;
      }

      public MessageBuilder appendOpenLink() {
         String url = notifier.getJenkinsUrl() + build.getUrl();
         message.append(" (<a href='").append(url).append("console'>Console</a>)");
         return this;
      }

      public MessageBuilder appendCommitLink() {
         try {
             List logs = build.getLog(100); // get last 100 lines of currentBuildLog
             Pattern pattern = Pattern.compile("^Commencing build of Revision (\\b\\w{40}\\b) \\(\\w+/(.+)\\)$");
             String commitId = new String();
             String commitBranch = new String();
             for (Object o : logs) {
                 String s = (String)o;
                 Matcher matcher = pattern.matcher(s);
                 if (matcher.find()) {
                     commitId = matcher.group(1);
                     commitBranch = matcher.group(2);
                     break;
                 }
             }
             if (!commitId.isEmpty()) { // otherwise we have no idea
                String githubUrl = "https://github.com/uservoice/uservoice/";
                message.append(" <a href='").append(githubUrl).append("compare/").append(commitBranch).append("'>")
                        .append(commitBranch).append("</a>/");
                message.append("<a href='").append(githubUrl).append("commit/").append(commitId).append("'>")
                        .append(commitId.substring(0, 6)).append("</a>");
             }
         } catch (IOException e) {
             logger.info("Could not read logs");
         }
         return this;
      }

      public MessageBuilder appendCommitInfo() {
         ChangeLogSet changeSet = build.getChangeSet();
         if ( !changeSet.isEmptySet() ) {
            List<Entry> entries = new LinkedList<Entry>();
            for(Object o : changeSet.getItems()) {
               Entry entry = (Entry)o;
               entries.add(entry);
            }
            String commitAuthor = entries.get((entries.size() - 1)).getAuthor().getDisplayName();
            String commitMsg = entries.get((entries.size() - 1)).getMsgEscaped();
            message.append(commitAuthor);
            message.append(": ");
            message.append(commitMsg);
         }
         return this;
      }

      public MessageBuilder appendDuration() {
         message.append(" after ");
         message.append(build.getDurationString());
         return this;
      }

      public String toString() {
         return message.toString();
      }
   }
}
