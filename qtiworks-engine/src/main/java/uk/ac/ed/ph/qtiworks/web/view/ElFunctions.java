/* Copyright (c) 2012, University of Edinburgh.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice, this
 *   list of conditions and the following disclaimer in the documentation and/or
 *   other materials provided with the distribution.
 *
 * * Neither the name of the University of Edinburgh nor the names of its
 *   contributors may be used to endorse or promote products derived from this
 *   software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *
 * This software is derived from (and contains code from) QTItools and MathAssessEngine.
 * QTItools is (c) 2008, University of Southampton.
 * MathAssessEngine is (c) 2010, University of Edinburgh.
 */
package uk.ac.ed.ph.qtiworks.web.view;

import uk.ac.ed.ph.jqtiplus.internal.util.DumpMode;
import uk.ac.ed.ph.jqtiplus.internal.util.ObjectDumper;
import uk.ac.ed.ph.jqtiplus.utils.contentpackaging.QtiContentPackageExtractor;
import uk.ac.ed.ph.jqtiplus.xmlutils.CustomUriScheme;

import java.net.URI;
import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.jsp.PageContext;

/**
 * Some convenience EL functions for the view/JSP layer.
 *
 * @author David McKain
 */
public final class ElFunctions {

    public static String extractContentPackagePath(final URI uri) {
        final CustomUriScheme packageUriScheme = QtiContentPackageExtractor.PACKAGE_URI_SCHEME;
        return packageUriScheme.uriToPath(uri);
    }

    public static String encodePageLink(final PageContext pageContext, final String pageName) {
        return escapeLink(ViewUtilities.createPageLink(getRequest(pageContext),
                ViewUtilities.decodePathName(pageName), null, null));
    }

    static String escapeLink(final String link) {
        return link.replace("&", "&amp;");
    }

    public static String dumpObject(final Object object) {
        return ObjectDumper.dumpObject(object, DumpMode.DEEP);
    }

    public static String formatTime(final Date time) {
        return ViewUtilities.getTimeFormat().format(time);
    }

    public static String formatDate(final Date time) {
        return ViewUtilities.getDateFormat().format(time);
    }

    public static String formatDateAndTime(final Date time) {
        return ViewUtilities.getDateAndTimeFormat().format(time);
    }

    public static String formatDayDateAndTime(final Date time) {
        return ViewUtilities.getDayDateAndTimeFormat().format(time);
    }

    private static HttpServletRequest getRequest(final PageContext pageContext) {
        return (HttpServletRequest) pageContext.getRequest();
    }
}