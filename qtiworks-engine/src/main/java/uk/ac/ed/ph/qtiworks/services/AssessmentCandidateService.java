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
package uk.ac.ed.ph.qtiworks.services;

import uk.ac.ed.ph.qtiworks.QtiWorksLogicException;
import uk.ac.ed.ph.qtiworks.base.services.Auditor;
import uk.ac.ed.ph.qtiworks.domain.DomainEntityNotFoundException;
import uk.ac.ed.ph.qtiworks.domain.IdentityContext;
import uk.ac.ed.ph.qtiworks.domain.Privilege;
import uk.ac.ed.ph.qtiworks.domain.PrivilegeException;
import uk.ac.ed.ph.qtiworks.domain.RequestTimestampContext;
import uk.ac.ed.ph.qtiworks.domain.binding.ItemSesssionStateXmlMarshaller;
import uk.ac.ed.ph.qtiworks.domain.dao.CandidateItemAttemptDao;
import uk.ac.ed.ph.qtiworks.domain.dao.CandidateItemEventDao;
import uk.ac.ed.ph.qtiworks.domain.dao.CandidateItemSessionDao;
import uk.ac.ed.ph.qtiworks.domain.dao.ItemDeliveryDao;
import uk.ac.ed.ph.qtiworks.domain.entities.Assessment;
import uk.ac.ed.ph.qtiworks.domain.entities.AssessmentPackage;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateFileSubmission;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemAttempt;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemEvent;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemEventType;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemResponse;
import uk.ac.ed.ph.qtiworks.domain.entities.CandidateItemSession;
import uk.ac.ed.ph.qtiworks.domain.entities.ItemDelivery;
import uk.ac.ed.ph.qtiworks.domain.entities.User;
import uk.ac.ed.ph.qtiworks.services.domain.CandidateSessionStateException;
import uk.ac.ed.ph.qtiworks.services.domain.CandidateSessionStateException.CSFailureReason;
import uk.ac.ed.ph.qtiworks.services.domain.ResponseBindingException;
import uk.ac.ed.ph.qtiworks.utils.XmlUtilities;

import uk.ac.ed.ph.jqtiplus.JqtiExtensionManager;
import uk.ac.ed.ph.jqtiplus.exception2.RuntimeValidationException;
import uk.ac.ed.ph.jqtiplus.internal.util.Assert;
import uk.ac.ed.ph.jqtiplus.resolution.ResolvedAssessmentItem;
import uk.ac.ed.ph.jqtiplus.running.ItemSessionController;
import uk.ac.ed.ph.jqtiplus.state.ItemSessionState;
import uk.ac.ed.ph.jqtiplus.types.FileResponseData;
import uk.ac.ed.ph.jqtiplus.types.Identifier;
import uk.ac.ed.ph.jqtiplus.types.ResponseData;
import uk.ac.ed.ph.jqtiplus.types.StringResponseData;

import uk.ac.ed.ph.snuggletex.XMLStringOutputOptions;
import uk.ac.ed.ph.snuggletex.internal.util.XMLUtilities;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Resource;
import javax.xml.parsers.DocumentBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Service the manages the real-time delivery of an {@link Assessment}
 * to a particular candidate {@link User}
 *
 * @author David McKain
 */
@Service
@Transactional(readOnly=false, propagation=Propagation.REQUIRED)
public class AssessmentCandidateService {

    private static final Logger logger = LoggerFactory.getLogger(AssessmentCandidateService.class);

    @Resource
    private Auditor auditor;

    @Resource
    private RequestTimestampContext requestTimestampContext;

    @Resource
    private IdentityContext identityContext;

    @Resource
    private AssessmentObjectManagementService assessmentObjectManagementService;

    @Resource
    private CandidateUploadService candidateUploadService;

    @Resource
    private JqtiExtensionManager jqtiExtensionManager;

    @Resource
    private ItemDeliveryDao itemDeliveryDao;

    @Resource
    private CandidateItemSessionDao candidateItemSessionDao;

    @Resource
    private CandidateItemEventDao candidateItemEventDao;

    @Resource
    private CandidateItemAttemptDao candidateItemAttemptDao;

    //----------------------------------------------------
    // Session creation and initialisation

    public ItemDelivery lookupItemDelivery(final long did)
            throws DomainEntityNotFoundException, PrivilegeException {
        final ItemDelivery itemDelivery = itemDeliveryDao.requireFindById(did);
        ensureCandidateMayAccess(itemDelivery);
        return itemDelivery;
    }

    /**
     * FIXME: Currently we're only allowing access to public or owned deliveries! This will need
     * to be relaxed in order to allow "real" deliveries to be done.
     */
    private User ensureCandidateMayAccess(final ItemDelivery itemDelivery) throws PrivilegeException {
        final User caller = identityContext.getCurrentThreadEffectiveIdentity();
        final Assessment assessment = itemDelivery.getAssessmentPackage().getAssessment();
        if (!assessment.isPublic() && !caller.equals(assessment.getOwner())) {
            throw new PrivilegeException(caller, Privilege.CANDIDATE_ACCESS_ITEM_DELIVERY, itemDelivery);
        }
        return caller;
    }

    public CandidateItemSession createCandidateSession(final ItemDelivery itemDelivery) {
        Assert.ensureNotNull(itemDelivery, "itemDelivery");

        final CandidateItemSession result = new CandidateItemSession();
        result.setCandidate(identityContext.getCurrentThreadEffectiveIdentity());
        result.setItemDelivery(itemDelivery);
        candidateItemSessionDao.persist(result);

        auditor.recordEvent("Created item session #" + result.getId());
        return result;
    }

    public ItemSessionState initialiseSession(final CandidateItemSession candidateSession) throws RuntimeValidationException {
        Assert.ensureNotNull(candidateSession, "candidateSession");

        final CandidateItemEvent mostRecentEvent = candidateItemEventDao.getNewestEventInSession(candidateSession);
        if (mostRecentEvent!=null) {
            /* FIXME: Need to add logic to decide whether we are allowed to re-initialize
             * the session or not
             */
        }

        /* Get the resolved JQTI+ Object for the underlying package */
        final AssessmentPackage assessmentPackage = candidateSession.getItemDelivery().getAssessmentPackage();
        final ResolvedAssessmentItem resolvedAssessmentItem = assessmentObjectManagementService.getResolvedAssessmentItem(assessmentPackage);

        /* Create new state */
        final ItemSessionState itemSessionState = new ItemSessionState();

        /* Initialise state */
        final ItemSessionController itemController = new ItemSessionController(jqtiExtensionManager, resolvedAssessmentItem, itemSessionState);
        itemController.initialize();

        /* Record event */
        recordEvent(candidateSession, CandidateItemEventType.INIT, itemSessionState);
        auditor.recordEvent("Initialized session #" + candidateSession);

        return itemSessionState;
    }

    //----------------------------------------------------
    // Session management

    public CandidateItemSession lookupCandidateSession(final long sessionId)
            throws DomainEntityNotFoundException, PrivilegeException {
        final CandidateItemSession session = candidateItemSessionDao.requireFindById(sessionId);
        ensureCallerMayAccess(session);
        return session;
    }

    /**
     * (Currently we're restricting access to sessions to their owners.)
     */
    private User ensureCallerMayAccess(final CandidateItemSession candidateSession) throws PrivilegeException {
        final User caller = identityContext.getCurrentThreadEffectiveIdentity();
        if (!caller.equals(candidateSession.getCandidate())) {
            throw new PrivilegeException(caller, Privilege.ACCESS_CANDIDATE_SESSION, candidateSession);
        }
        return caller;
    }

    //----------------------------------------------------
    // Attempt

    public CandidateFileSubmission importFileResponse(final CandidateItemSession candidateSession, final MultipartFile multipartFile) {
        Assert.ensureNotNull(candidateSession, "candidateSession");
        Assert.ensureNotNull(multipartFile, "multipartFile");

        return candidateUploadService.importFileSubmission(candidateSession, multipartFile);
    }

    public CandidateItemAttempt handleAttempt(final CandidateItemSession candidateSession,
            final Map<Identifier, List<String>> stringResponseMap,
            final Map<Identifier, CandidateFileSubmission> fileResponseMap)
            throws RuntimeValidationException, CandidateSessionStateException, ResponseBindingException {
        Assert.ensureNotNull(candidateSession, "candidateSession");

        /* Check current state */
        final ItemSessionState itemSessionState = getCurrentItemSessionState(candidateSession);
        if (itemSessionState==null) {
            throw new CandidateSessionStateException(candidateSession, CSFailureReason.ATTEMPT_NOT_ALLOWED);
        }
        final AssessmentPackage assessmentPackage = candidateSession.getItemDelivery().getAssessmentPackage();
        final ResolvedAssessmentItem resolvedAssessmentItem = assessmentObjectManagementService.getResolvedAssessmentItem(assessmentPackage);

        /* FIXME: Need more rigorous state checking, e.g. no attempt after complete etc. */

        /* Build response map in required format for JQTI+.
         * NB: The following doesn't test for duplicate keys in the two maps. I'm not sure
         * it's worth the effort.
         */
        final Map<Identifier, ResponseData> responseMap = new HashMap<Identifier, ResponseData>();
        if (stringResponseMap!=null) {
            for (final Entry<Identifier, List<String>> stringResponseEntry : stringResponseMap.entrySet()) {
                final Identifier identifier = stringResponseEntry.getKey();
                final List<String> stringResponses = stringResponseEntry.getValue();
                final StringResponseData stringResponseData = new StringResponseData(stringResponses);
                responseMap.put(identifier, stringResponseData);
            }
        }
        if (fileResponseMap!=null) {
            for (final Entry<Identifier, CandidateFileSubmission> fileResponseEntry : fileResponseMap.entrySet()) {
                final Identifier identifier = fileResponseEntry.getKey();
                final CandidateFileSubmission fileSubmission = fileResponseEntry.getValue();
                final FileResponseData fileResponseData = new FileResponseData(new File(fileSubmission.getStoredFilePath()), fileSubmission.getContentType());
                responseMap.put(identifier, fileResponseData);
            }
        }

        /* Attempt to bind responses */
        final ItemSessionController itemSessionController = new ItemSessionController(jqtiExtensionManager, resolvedAssessmentItem, itemSessionState);
        final List<Identifier> badResponseIdentifiers = itemSessionController.bindResponses(responseMap);
        if (!badResponseIdentifiers.isEmpty()) {
            /* Some responses could not be bound.
             *
             * (This would happen if the client sends the wrong type of response data. This is
             * NOT the fault of the candidate - it is either caused by the API caller or the
             * rendering layer.)
             */
            final Map<Identifier, ResponseData> badResponseMap = new HashMap<Identifier, ResponseData>();
            for (final Identifier badResponseIdentifier : badResponseIdentifiers) {
                badResponseMap.put(badResponseIdentifier, responseMap.get(badResponseIdentifier));
            }
            auditor.recordEvent("Failed to bind response data on session #" + candidateSession.getId());
            throw new ResponseBindingException(candidateSession, badResponseMap);
        }

        /* All responses were bound successfully, so we'll treat this is an attempt */
        final CandidateItemAttempt candidateItemAttempt = new CandidateItemAttempt();
        final List<CandidateItemResponse> candidateItemResponses = new ArrayList<CandidateItemResponse>();
        for (final Entry<Identifier, ResponseData> responseEntry : responseMap.entrySet()) {
            final Identifier responseIdentifier = responseEntry.getKey();
            final ResponseData responseData = responseEntry.getValue();

            final CandidateItemResponse candidateItemResponse = new CandidateItemResponse();
            candidateItemResponse.setResponseIdentifier(responseIdentifier.toString());
            candidateItemResponse.setAttempt(candidateItemAttempt);
            candidateItemResponse.setResponseType(responseData.getType());
            switch (responseData.getType()) {
                case STRING:
                    candidateItemResponse.setStringResponseData(((StringResponseData) responseData).getResponseData());
                    break;

                case FILE:
                    candidateItemResponse.setFileSubmission(fileResponseMap.get(responseIdentifier));
                    break;

                default:
                    throw new QtiWorksLogicException("Unexpected switch case: " + responseData.getType());
            }
            candidateItemResponses.add(candidateItemResponse);
        }

        /* Now validate the responses according to any constraints specified by the interactions */
        final Set<Identifier> invalidResponseIdentifiers = itemSessionController.validateResponses();
        final boolean areResponsesValid = invalidResponseIdentifiers.isEmpty();
        if (!areResponsesValid) {
            /* Some responses not valid, so note these down */
            final Set<String> invalidResponseIdentifierStrings = new HashSet<String>();
            for (final Identifier invalidResponseIdentifier : invalidResponseIdentifiers) {
                invalidResponseIdentifierStrings.add(invalidResponseIdentifier.toString());
            }
            candidateItemAttempt.setInvalidResponseIdentifiers(invalidResponseIdentifierStrings);
        }

        /* Invoke response processing (only if responses are valid) */
        if (areResponsesValid) {
            itemSessionController.processResponses();
        }

        /* Record resulting attempt and event */
        final CandidateItemEvent candidateItemEvent = recordEvent(candidateSession,
                areResponsesValid ? CandidateItemEventType.VALID_ATTEMPT : CandidateItemEventType.INVALID_ATTEMPT,
                itemSessionState);

        candidateItemAttempt.setEvent(candidateItemEvent);
        candidateItemAttemptDao.persist(candidateItemAttempt);
        return candidateItemAttempt;
    }

    //----------------------------------------------------
    // Utilities

    private ItemSessionState getCurrentItemSessionState(final CandidateItemSession candidateSession) {
        final CandidateItemEvent mostRecentEvent = candidateItemEventDao.getNewestEventInSession(candidateSession);
        if (mostRecentEvent==null) {
            return null;
        }
        return unmarshalItemSessionState(mostRecentEvent);
    }

    private ItemSessionState unmarshalItemSessionState(final CandidateItemEvent event) {
        final String itemSessionStateXml = event.getItemSessionStateXml();

        final DocumentBuilder documentBuilder = XmlUtilities.createNsAwareDocumentBuilder();
        Document doc;
        try {
            doc = documentBuilder.parse(new InputSource(new StringReader(itemSessionStateXml)));
        }
        catch (final Exception e) {
            throw new QtiWorksLogicException("Could not parse ItemSessionState XML. This is an internal error as we currently don't expose this data to clients", e);
        }
        return ItemSesssionStateXmlMarshaller.unmarshal(doc);
    }

    private CandidateItemEvent recordEvent(final CandidateItemSession candidateSession,
            final CandidateItemEventType eventType, final ItemSessionState itemSessionState) {
        final CandidateItemEvent event = new CandidateItemEvent();
        event.setCandidateItemSession(candidateSession);
        event.setEventType(eventType);

        event.setCompletionStatus(itemSessionState.getCompletionStatus());
        event.setDuration(itemSessionState.getDuration());
        event.setNumAttempts(itemSessionState.getNumAttempts());
        event.setTimestamp(requestTimestampContext.getCurrentRequestTimestamp());

        /* Record serialized ItemSessionState */
        event.setItemSessionStateXml(marshalItemSessionState(itemSessionState));

        /* Store */
        candidateItemEventDao.persist(event);
        logger.debug("Recorded {}", event);
        return event;
    }

    private String marshalItemSessionState(final ItemSessionState itemSessionState) {
        final Document marshalledState = ItemSesssionStateXmlMarshaller.marshal(itemSessionState);
        final XMLStringOutputOptions xmlOptions = new XMLStringOutputOptions();
        xmlOptions.setIndenting(true);
        xmlOptions.setIncludingXMLDeclaration(false);
        return XMLUtilities.serializeNode(marshalledState, xmlOptions);
    }
}
