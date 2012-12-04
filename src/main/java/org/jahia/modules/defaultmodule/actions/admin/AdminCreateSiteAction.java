package org.jahia.modules.defaultmodule.actions.admin;

import org.apache.commons.lang.StringUtils;
import org.jahia.admin.sites.ManageSites;
import org.jahia.bin.ActionResult;
import org.jahia.bin.JahiaAdministration;
import org.jahia.exceptions.JahiaException;
import org.jahia.params.ProcessingContext;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.jahia.services.sites.*;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.utils.LanguageCodeConverters;
import org.jahia.utils.Url;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.*;

/**
 * Delete a site.
 */
public class AdminCreateSiteAction extends AdminAction {
    private static Logger logger = LoggerFactory.getLogger(AdminCreateSiteAction.class);

    protected JahiaSitesService sitesService;

    public void setSitesService(JahiaSitesService sitesService) {
        this.sitesService = sitesService;
    }

    @Override
    public ActionResult doExecute(HttpServletRequest req, RenderContext renderContext, Resource resource, JCRSessionWrapper session, Map<String, List<String>> parameters, URLResolver urlResolver) throws Exception {
        JCRNodeWrapper node = resource.getNode();

        if (!node.isNodeType("jnt:virtualsitesFolder")  || !node.getPath().equals("/sites")) {
            return ActionResult.BAD_REQUEST;
        }

        logger.debug("started");

        // get form values...
        String siteTitle = StringUtils.left(StringUtils.defaultString(getParameter(parameters, "siteTitle")).trim(), 100);
        String siteServerName = StringUtils.left(StringUtils.defaultString(getParameter(parameters, "siteServerName")).trim(), 200);
        String siteKey = StringUtils.left(StringUtils.defaultString(getParameter(parameters, "siteKey")).trim(), 50);
        String siteDescr = StringUtils.left(StringUtils.defaultString(getParameter(parameters, "siteDescr")).trim(), 250);

        Map result = new HashMap();

        JahiaSite site = null;
        // create jahia site object if checks are in green light...
        try {
            // check validity...
            if (siteTitle != null && (siteTitle.length() > 0) && siteServerName != null &&
                    (siteServerName.length() > 0) && siteKey != null && (siteKey.length() > 0)) {
                if (!ManageSites.isSiteKeyValid(siteKey)) {
                    result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.onlyLettersDigitsUnderscore.label"));
                    return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
                } else if (siteKey.equals("site")) {
                    result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.chooseAnotherSiteKey.label"));
                    return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
                } else if (!ManageSites.isServerNameValid(siteServerName)) {
                    result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.invalidServerName.label"));
                    return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
                } else if (siteServerName.equals("default")) {
                    result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.chooseAnotherServerName.label"));
                    return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
                } else if (!Url.isLocalhost(siteServerName) && sitesService.getSite(siteServerName) != null) {
                    result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.chooseAnotherServerName.label"));
                    return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
                } else if (sitesService.getSiteByKey(siteKey) != null) {
                    result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.chooseAnotherSiteKey.label"));
                    return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
                }
            } else {
                result.put("warn", getMessage(renderContext.getUILocale(), "org.jahia.admin.warningMsg.completeRequestInfo.label"));
                return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
            }

            // save new jahia site...
            site = new JahiaSite(-1, siteTitle, siteServerName, siteKey, siteDescr, null, null);

            Boolean defaultSite = false;
            Locale selectedLocale = resource.getLocale();
            String lang = getParameter(parameters, "language");
            if (lang != null) {
                selectedLocale = LanguageCodeConverters.getLocaleFromCode(lang);
            }

            // get services...
            JahiaUserManagerService jums = ServicesRegistry.getInstance().getJahiaUserManagerService();

            // add the site in siteManager...
            site = sitesService.addSite(session.getUser(), site.getTitle(), site.getServerName(), site.getSiteKey(), site.getDescr(),
                    selectedLocale, getParameter(parameters,"templatesSet"),
                    null,null, null,null, false, null, null);

            if (getParameter(parameters, "mixLanguage", "false").equals("true") || getParameter(parameters, "allowsUnlistedLanguages", "false").equals("true")) {
                site.setMixLanguagesActive(getParameter(parameters, "mixLanguage", "false").equals("true"));
                site.setAllowsUnlistedLanguages(getParameter(parameters, "allowsUnlistedLanguages", "false").equals("true"));
                sitesService.updateSite(site);
            }

            if (site != null) {
                // set as default site
                if (defaultSite.booleanValue()) {
                    sitesService.setDefaultSite(site);
                }

                JahiaSite systemSite = sitesService.getSiteByKey(JahiaSitesBaseService.SYSTEM_SITE_KEY);
                // update the system site only if it does not yet contain at least one of the site languages
                if (!systemSite.getLanguages().containsAll(site.getLanguages())) {
                    systemSite.getLanguages().addAll(site.getLanguages());
                    sitesService.updateSite(systemSite);
                }
            } else {
                result.put("warn", getMessage(renderContext.getUILocale(), "label.error.processingRequestError"));
                return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
            }
        } catch (JahiaException ex) {
            try {
                if (site != null) {
                    sitesService.removeSite(site);
                }
            } catch (Exception t) {
                logger.error("Error while cleaning site", t);
            }

            logger.error("Error while adding site", ex);

            result.put("warn", getMessage(renderContext.getUILocale(), "label.error.processingRequestError"));
            return new ActionResult(HttpServletResponse.SC_OK, null, new JSONObject(result));
        }

        return ActionResult.OK_JSON;
    }


}
