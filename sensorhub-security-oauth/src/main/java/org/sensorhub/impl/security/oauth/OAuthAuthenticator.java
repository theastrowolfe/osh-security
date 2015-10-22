/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.security.oauth;

import java.io.IOException;
import java.io.StringReader;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.oltu.oauth2.client.OAuthClient;
import org.apache.oltu.oauth2.client.URLConnectionClient;
import org.apache.oltu.oauth2.client.request.OAuthBearerClientRequest;
import org.apache.oltu.oauth2.client.request.OAuthClientRequest;
import org.apache.oltu.oauth2.client.response.OAuthAuthzResponse;
import org.apache.oltu.oauth2.client.response.OAuthJSONAccessTokenResponse;
import org.apache.oltu.oauth2.client.response.OAuthResourceResponse;
import org.apache.oltu.oauth2.common.OAuth.HttpMethod;
import org.apache.oltu.oauth2.common.OAuthProviderType;
import org.apache.oltu.oauth2.common.exception.OAuthProblemException;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.apache.oltu.oauth2.common.message.types.GrantType;
import org.eclipse.jetty.security.ServerAuthException;
import org.eclipse.jetty.security.UserAuthentication;
import org.eclipse.jetty.security.authentication.LoginAuthenticator;
import org.eclipse.jetty.security.authentication.SessionAuthentication;
import org.eclipse.jetty.server.Authentication;
import org.eclipse.jetty.server.Authentication.User;
import org.eclipse.jetty.server.UserIdentity;
import com.google.gson.stream.JsonReader;


public class OAuthAuthenticator extends LoginAuthenticator// implements Authenticator
{
    private static final String GOOGLE_CLIENT_ID = "756042811191-k5q4tc6usq8dtkjpquq4ue78eq5mtru7.apps.googleusercontent.com";
    private static final String GOOGLE_CLIENT_SECRET = "7bIXZed1WZmDu9v5my-SYEYD";
    private static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/userinfo.profile";
    private static final String AUTH_METHOD_OAUTH2 = "OAUTH2";


    @Override
    public String getAuthMethod()
    {
        return AUTH_METHOD_OAUTH2;
    }


    @Override
    public void prepareRequest(ServletRequest arg0)
    {

    }


    @Override
    public boolean secureResponse(ServletRequest arg0, ServletResponse arg1, boolean arg2, User arg3) throws ServerAuthException
    {
        return false;
    }


    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse resp, boolean mandatory) throws ServerAuthException
    {
        try
        {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) resp;
            String redirectUrl = request.getRequestURL().toString();
            HttpSession session = request.getSession(true);
            
            // check for cached auth
            Authentication cachedAuth = (Authentication) session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
            if (cachedAuth != null && cachedAuth instanceof Authentication.User)
            {
                if (!_loginService.validate(((Authentication.User)cachedAuth).getUserIdentity()))
                    session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
                else
                    return cachedAuth;
            }
            
            // if calling back from provider with oauth code
            if (request.getParameter("code") != null)
            {
                try
                {
                    // first request temporary access token
                    OAuthAuthzResponse oar = OAuthAuthzResponse.oauthCodeAuthzResponse(request);
                    String code = oar.getCode();
                    System.out.println("Code: " + code);

                    OAuthClientRequest authRequest = OAuthClientRequest.tokenProvider(OAuthProviderType.GOOGLE).setCode(code).setClientId(GOOGLE_CLIENT_ID).setClientSecret(GOOGLE_CLIENT_SECRET)
                            .setRedirectURI(redirectUrl).setGrantType(GrantType.AUTHORIZATION_CODE).buildBodyMessage();

                    OAuthClient oAuthClient = new OAuthClient(new URLConnectionClient());
                    OAuthJSONAccessTokenResponse oAuthResponse = oAuthClient.accessToken(authRequest, HttpMethod.POST);

                    // read access token and store in session
                    String accessToken = oAuthResponse.getAccessToken();
                    long expiresIn = oAuthResponse.getExpiresIn();
                    System.out.println("Token: " + accessToken + ", Expires in " + expiresIn);

                    // request user info
                    OAuthClientRequest bearerClientRequest = new OAuthBearerClientRequest("https://www.googleapis.com/oauth2/v1/userinfo").setAccessToken(accessToken).buildQueryMessage();
                    OAuthResourceResponse resourceResponse = oAuthClient.resource(bearerClientRequest, HttpMethod.GET, OAuthResourceResponse.class);

                    // parse user info
                    System.out.println("UserInfo: " + resourceResponse.getBody());
                    JsonReader jsonReader = new JsonReader(new StringReader(resourceResponse.getBody()));
                    String userId = parseUserInfoJson(jsonReader);
                    
                    // login and return UserAuth object
                    UserIdentity user = login(userId, "NONE", req);
                    if (user != null)
                    {
                        UserAuthentication userAuth = new UserAuthentication(getAuthMethod(), user);
                        session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, userAuth);
                        return userAuth;
                    }
                    
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return Authentication.SEND_FAILURE;
                }
                catch (OAuthProblemException | OAuthSystemException e)
                {
                    e.printStackTrace();
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return Authentication.SEND_FAILURE;
                }
            }

            // first login at auth provider
            else
            {
                try
                {
                    // generate request to auth provider
                    OAuthClientRequest authRequest = OAuthClientRequest.authorizationProvider(OAuthProviderType.GOOGLE).setClientId(GOOGLE_CLIENT_ID).setRedirectURI(redirectUrl)
                            .setResponseType("code").setScope(GOOGLE_SCOPE).buildQueryMessage();

                    // send as redirect
                    String loginUrl = authRequest.getLocationUri();
                    response.sendRedirect(response.encodeRedirectURL(loginUrl));
                    return Authentication.SEND_CONTINUE;
                }
                catch (OAuthSystemException e)
                {
                    e.printStackTrace();
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return Authentication.SEND_FAILURE;
                }
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
            throw new ServerAuthException(e);
        }
    }


    private String parseUserInfoJson(JsonReader reader) throws IOException
    {
        String userId = null;

        reader.beginObject();        
        while (reader.hasNext())
        {
            String name = reader.nextName();
            if (name.equals("id"))
                userId = reader.nextString();
            else
                reader.skipValue();
        }        
        reader.endObject();
        
        return userId;
    }

}