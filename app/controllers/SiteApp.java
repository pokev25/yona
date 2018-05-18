/**
 * Yona, 21st Century Project Hosting SW
 * <p>
 * Copyright Yona & Yobi Authors & NAVER Corp.
 * https://yona.io
 **/
package controllers;

import com.avaje.ebean.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import controllers.annotation.AnonymousCheck;
import data.DataService;
import info.schleichardt.play2.mailplugin.Mailer;
import models.*;
import models.enumeration.State;
import models.enumeration.UserState;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.format.datetime.DateFormatter;
import play.Configuration;
import play.Logger;
import play.db.ebean.Transactional;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;
import scala.App;
import utils.*;
import views.html.site.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static play.libs.Json.toJson;

/**
 * The Class SiteApp.
 */
@With(SiteManagerAuthAction.class)
@AnonymousCheck
public class SiteApp extends Controller {

    private static final int PROJECT_COUNT_PER_PAGE = 25;
    private static final int POSTING_COUNT_PER_PAGE = 30;
    private static final int ISSUE_COUNT_PER_PAGE = 30;

    /**
     * @return the result
     * @throws EmailException the email exception
     * @see {@link SiteApp#writeMail(String, boolean)}
     */
    public static Result sendMail() throws EmailException{
        SimpleEmail email = new SimpleEmail();

        Map<String, String[]> formData = request().body().asFormUrlEncoded();
        email.setFrom(utils.HttpUtil.getFirstValueFromQuery(formData, "from"));
        email.setSubject(utils.HttpUtil.getFirstValueFromQuery(formData, "subject"));
        email.addTo(utils.HttpUtil.getFirstValueFromQuery(formData, "to"));
        email.setMsg(utils.HttpUtil.getFirstValueFromQuery(formData, "body"));
        email.setCharset("utf-8");

        String errorMessage = null;
        boolean sended;
        String result = Mailer.send(email);
        Logger.info(">>>" + result);
        sended = true;
        return writeMail(errorMessage, sended);
    }

    public static Result writeMail(String errorMessage, boolean sended) {

        Configuration config = play.Play.application().configuration();
        List<String> notConfiguredItems = new ArrayList<>();
        String[] requiredItems = {"smtp.host", "smtp.user", "smtp.password"};
        for(String key : requiredItems) {
            if (config.getString(key) == null) {
                notConfiguredItems.add(key);
            }
        }

        String sender = utils.Config.getEmailFromSmtp();

        return ok(mail.render("title.sendMail", notConfiguredItems, sender, errorMessage, sended));
    }

    public static Result massMail() {
        return ok(massMail.render("title.massMail"));
    }

    /**
     * @param pageNum pager number
     * @param loginId loginId
     * @return the result
     * @see {@link User#findUsers(int, String)}
     */
    public static Result userList(int pageNum, String query) {
        String state = StringUtils.defaultIfBlank(request().getQueryString("state"), UserState.ACTIVE.name());
        UserState userState = UserState.valueOf(state);
        Page<User> users = User.findUsers(pageNum -1, query, userState);
        return ok(userList.render("title.siteSetting", users, userState, query));
    }

    /**
     * @param pageNum page number
     * @return the result
     */
    public static Result postList(int pageNum) {
        Page<Posting> page = Posting.finder.order("createdDate DESC").findPagingList(POSTING_COUNT_PER_PAGE).getPage(pageNum - 1);
        return ok(postList.render("title.siteSetting", page));
    }

    /**
     * @param pageNum page number
     * @return the result
     */
    public static Result issueList(int pageNum) {
        String state = StringUtils.defaultIfBlank(request().getQueryString("state"), State.OPEN.name());
        State currentState = State.valueOf(state.toUpperCase());
        Page<Issue> page = Issue.findIssuesByState(ISSUE_COUNT_PER_PAGE, pageNum - 1, currentState);
        return ok(issueList.render("title.siteSetting", page, currentState));
    }

    /**
     * @param userId the user id
     * @return the result
     * @see {@link Project#isOnlyManager(Long)}
     */
    @Transactional
    public static Result deleteUser(Long userId) {
        if (User.findByLoginId(session().get("loginId")).isSiteManager()){
            if (Project.isOnlyManager(userId)) {
                flash(Constants.WARNING, "site.userList.deleteAlert");
                return forbidden();
            } else {
                User user = User.find.byId(userId);
                for (ProjectUser projectUser : user.projectUser) {
                    projectUser.delete();
                }
                user.changeState(UserState.DELETED);

                return redirect(routes.SiteApp.userList(1, null));
            }
        } else {
            flash(Constants.WARNING, "error.auth.unauthorized.waringMessage");
            return forbidden();
        }
    }

    /**
     * @param projectName the project name
     * @param pageNum page number
     * @return the result
     * @see {@link Project#findByName(String, int, int)}
     */
    public static Result projectList(String projectName, int pageNum) {
        Page<Project> projects = Project.findByName(projectName, PROJECT_COUNT_PER_PAGE, pageNum);
        return ok(projectList.render("title.projectList", projects, projectName));
    }

    /**
     * @param projectId the project id
     * @return the result
     */
    @Transactional
    public static Result deleteProject(Long projectId){
        if( User.findByLoginId(session().get("loginId")).isSiteManager() ){
            Project.find.byId(projectId).delete();
        } else {
            flash(Constants.WARNING, "error.auth.unauthorized.waringMessage");
        }
        return redirect(routes.SiteApp.projectList(StringUtils.EMPTY, 0));
    }

    /**
     * @param loginId the login id
     * @return the result
     */

    public static Result toggleAccountLock(String loginId, String state, String query){
        String stateParam = StringUtils.defaultIfBlank(state, UserState.ACTIVE.name());
        UserState userState = UserState.valueOf(stateParam);

        if(User.findByLoginId(session().get("loginId")).isSiteManager()){
            User targetUser = User.findByLoginId(loginId);
            if (targetUser.isAnonymous()){
                flash(Constants.WARNING, "user.notExists.name");
                return redirect(routes.SiteApp.userList(0, null));
            }
            if (targetUser.state == UserState.ACTIVE) {
                targetUser.changeState(UserState.LOCKED);
            } else {
                targetUser.changeState(UserState.ACTIVE);
            }
            return ok(userList.render("title.siteSetting", User.findUsers(0, query, userState), userState, query));
        }
        flash(Constants.WARNING, "error.auth.unauthorized.waringMessage");
        return redirect(routes.Application.index());
    }

    public static Result toggleGuestMode(String loginId, String state, String query){
        String stateParam = StringUtils.defaultIfBlank(state, UserState.ACTIVE.name());
        UserState userState = UserState.valueOf(stateParam);
        if(User.findByLoginId(session().get("loginId")).isSiteManager()){
            User targetUser = User.findByLoginId(loginId);
            if (targetUser.isAnonymous()){
                flash(Constants.WARNING, "user.notExists.name");
                return redirect(routes.SiteApp.userList(0, null));
            }
            targetUser.isGuest = !targetUser.isGuest;
            targetUser.update();
            CacheStore.yonaUsers.put(targetUser.id, targetUser);
            return ok(userList.render("title.siteSetting", User.findUsers(0, query, userState), userState, query));
        }
        flash(Constants.WARNING, "error.auth.unauthorized.waringMessage");
        return redirect(routes.Application.index());
    }

    public static Result mailList() {
        Set<String> emails = new HashSet<>();
        Map<String, String[]> projects = request().body().asFormUrlEncoded();
        if(!UserApp.currentUser().isSiteManager()) {
            return forbidden(ErrorViews.Forbidden.render("error.auth.unauthorized.waringMessage"));
        }

        if (!request().accepts("application/json")) {
            return status(Http.Status.NOT_ACCEPTABLE);
        }

        if (projects == null) {
            return ok(toJson(new HashSet<String>()));
        }

        if (projects.containsKey("all")) {
            if (projects.get("all")[0].equals("true")) {
                for(User user : User.find.findList()) {
                    emails.add(user.email);
                }
            }
        } else {
            for(String[] projectNames : projects.values()) {
                String projectName = projectNames[0];
                String[] parts = projectName.split("/");
                String owner = parts[0];
                String name = parts[1];
                Project project = Project.findByOwnerAndProjectName(owner, name);
                for (ProjectUser projectUser : ProjectUser.findMemberListByProject(project.id)) {
                    Logger.debug(projectUser.user.email);
                    emails.add(projectUser.user.email);
                }
            }
        }

        return ok(toJson(emails));
    }

    /**
     * Hide the notification for Yobi updates.
     */
    public static Result unwatchUpdate() {
        YobiUpdate.isWatched = false;
        return ok();
    }

    /**
     * Show the page to update Yobi.
     */
    public static Result update() throws GitAPIException {
        String currentVersion = null;
        Exception exception = null;

        try {
            boolean useUpdateCheck = play.Configuration.root().getBoolean("application.update.check.use");
            if(useUpdateCheck){
                currentVersion = Config.getCurrentVersion();
                YobiUpdate.refreshVersionToUpdate();
            }
        } catch (Exception e) {
            exception = e;
        }

        return ok(update.render("title.siteSetting", currentVersion,
                    YobiUpdate.versionToUpdate, exception));
    }

    /**
     * Diagnose Yobi
     * @return
     */
    public static Result diagnose() {
        return ok(diagnostic.render("title.siteSetting", Diagnostic.checkAll()));
    }

    public static Result data() {
        return ok(data.render("title.siteSetting"));
    }

    public static Result exportData() throws JsonProcessingException {
        Date date = new Date();
        DateFormatter formatter = new DateFormatter("yyyyMMddHHmm");
        String formattedDate = formatter.print(date, Locale.getDefault());

        InputStream in = new DataService().exportData();
        response().setContentType("application/x-download");
        response().setHeader("Content-disposition","attachment; filename=yobi-data-" + formattedDate + ".json");

        return ok(in);
    }

    public static Result importData() throws IOException {
        Http.MultipartFormData body = request().body().asMultipartFormData();
        Http.MultipartFormData.FilePart yobiData = body.getFile("data");
        if (yobiData != null) {
            File file = yobiData.getFile();
            try {
                new DataService().importData(file);
                return redirect(routes.Application.index());
            } catch (Exception e) {
                return badRequest(ErrorViews.BadRequest.render());
            }
        } else {
            return redirect(routes.SiteApp.data());
        }
    }

    public static Result noAvatarUsers() {
        List<User> users = User.find.where().eq("state", UserState.ACTIVE).findList();
        List<ObjectNode> usersNode = new ArrayList<>();

        ObjectNode result = Json.newObject();

        for(User user: users){
            if(user.avatarId() == null) {
                usersNode.add(composeUserNode(user));
            }
        }

        result.put("users", toJson(usersNode));
        return ok(result);
    }

    private static ObjectNode composeUserNode(User user) {
        ObjectNode userNode = Json.newObject();
        userNode.put("loginId", user.loginId);
        userNode.put("name", user.name);
        userNode.put("email", user.email);
        return userNode;
    }

    public static Result setAttachmentToUserAvatar() {
        ObjectNode result = Json.newObject();

        JsonNode json = request().body().asJson();
        if (json == null) {
            return badRequest(result.put("message", "Expecting Json data"));
        }

        long avatarFileId = json.findValue("avatarFileId").asLong();
        Attachment attachment = Attachment.find.byId(avatarFileId);
        String primary = attachment.mimeType.split("/")[0].toLowerCase();

        String targetUserEmail = json.findValue("email").asText();
        User targetUser = User.findByEmail(targetUserEmail);

        if (primary.equals("image") && !targetUser.isAnonymous()) {
            Attachment.deleteAll(targetUser.avatarAsResource());
            attachment.moveTo(targetUser.avatarAsResource());
        }

        result.put("status", 200);
        result.put("message", "OK");
        return ok(result);
    }
}
