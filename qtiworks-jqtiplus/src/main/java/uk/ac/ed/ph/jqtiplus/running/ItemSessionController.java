/* Copyright (c) 2012-2013, University of Edinburgh.
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
package uk.ac.ed.ph.jqtiplus.running;

import uk.ac.ed.ph.jqtiplus.JqtiExtensionManager;
import uk.ac.ed.ph.jqtiplus.JqtiExtensionPackage;
import uk.ac.ed.ph.jqtiplus.JqtiLifecycleEventType;
import uk.ac.ed.ph.jqtiplus.QtiConstants;
import uk.ac.ed.ph.jqtiplus.exception.QtiCandidateStateException;
import uk.ac.ed.ph.jqtiplus.exception.ResponseBindingException;
import uk.ac.ed.ph.jqtiplus.exception.TemplateProcessingInterrupt;
import uk.ac.ed.ph.jqtiplus.internal.util.Assert;
import uk.ac.ed.ph.jqtiplus.node.item.AssessmentItem;
import uk.ac.ed.ph.jqtiplus.node.item.interaction.EndAttemptInteraction;
import uk.ac.ed.ph.jqtiplus.node.item.interaction.Interaction;
import uk.ac.ed.ph.jqtiplus.node.item.interaction.Shuffleable;
import uk.ac.ed.ph.jqtiplus.node.item.interaction.choice.Choice;
import uk.ac.ed.ph.jqtiplus.node.item.response.declaration.ResponseDeclaration;
import uk.ac.ed.ph.jqtiplus.node.item.response.processing.ResponseProcessing;
import uk.ac.ed.ph.jqtiplus.node.item.template.declaration.TemplateDeclaration;
import uk.ac.ed.ph.jqtiplus.node.item.template.processing.TemplateProcessing;
import uk.ac.ed.ph.jqtiplus.node.item.template.processing.TemplateProcessingRule;
import uk.ac.ed.ph.jqtiplus.node.outcome.declaration.OutcomeDeclaration;
import uk.ac.ed.ph.jqtiplus.node.result.AssessmentResult;
import uk.ac.ed.ph.jqtiplus.node.result.Context;
import uk.ac.ed.ph.jqtiplus.node.result.ItemResult;
import uk.ac.ed.ph.jqtiplus.node.result.ItemVariable;
import uk.ac.ed.ph.jqtiplus.node.result.OutcomeVariable;
import uk.ac.ed.ph.jqtiplus.node.result.ResponseVariable;
import uk.ac.ed.ph.jqtiplus.node.result.SessionIdentifier;
import uk.ac.ed.ph.jqtiplus.node.result.SessionStatus;
import uk.ac.ed.ph.jqtiplus.node.result.TemplateVariable;
import uk.ac.ed.ph.jqtiplus.node.shared.VariableDeclaration;
import uk.ac.ed.ph.jqtiplus.node.test.TemplateDefault;
import uk.ac.ed.ph.jqtiplus.resolution.RootNodeLookup;
import uk.ac.ed.ph.jqtiplus.state.ItemProcessingMap;
import uk.ac.ed.ph.jqtiplus.state.ItemSessionState;
import uk.ac.ed.ph.jqtiplus.types.Identifier;
import uk.ac.ed.ph.jqtiplus.types.ResponseData;
import uk.ac.ed.ph.jqtiplus.value.BooleanValue;
import uk.ac.ed.ph.jqtiplus.value.Signature;
import uk.ac.ed.ph.jqtiplus.value.Value;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High level controller for running an {@link AssessmentItem}.
 * <p>
 * Rendering and delivery engines will probably want to use this to perform the core
 * QTI processing.
 *
 * <h2>Basic workflow</h2>
 *
 * {@link #initialize(Date)}
 * {@link #performTemplateProcessing(Date)} or {@link #performTemplateProcessing(Date, List)}
 * {@link #enterItem(Date)}
 * {@link #touchDuration(Date)} (call before rendering to update duration)
 * {@link #bindResponses(Date, Map)}
 * {@link #commitResponses(Date)}
 * {@link #performResponseProcessing(Date)}
 * {@link #endItem(Date)} (maybe)
 * {@link #exitItem(Date)}
 * {@link #computeAssessmentResult()}
 *
 * Also available:
 *
 * {@link #resetItemSessionHard(Date, boolean)}
 * {@link #resetItemSessionSoft(Date, boolean)}
 * {@link #resetResponses(Date)}
 *
 * <h2>Usage</h2>
 *
 * An instance of this class may only be used by one thread at a time.
 *
 * @author David McKain
 */
public final class ItemSessionController extends ItemProcessingController implements ItemProcessingContext {

    private static final Logger logger = LoggerFactory.getLogger(ItemSessionController.class);

    private final ItemSessionControllerSettings itemSessionControllerSettings;

    public ItemSessionController(final JqtiExtensionManager jqtiExtensionManager,
            final ItemSessionControllerSettings itemSessionControllerSettings,
            final ItemProcessingMap itemProcessingMap, final ItemSessionState itemSessionState) {
        super(jqtiExtensionManager, itemProcessingMap, itemSessionState);
        Assert.notNull(itemSessionControllerSettings, "itemSessionControllerSettings");
        this.itemSessionControllerSettings = new ItemSessionControllerSettings(itemSessionControllerSettings); /* (Private copy) */
    }

    public ItemSessionControllerSettings getItemSessionControllerSettings() {
        return itemSessionControllerSettings;
    }

    //-------------------------------------------------------------------

    private void fireJqtiLifecycleEvent(final JqtiLifecycleEventType eventType) {
        for (final JqtiExtensionPackage<?> jqtiExtensionPackage : jqtiExtensionManager.getExtensionPackages()) {
            jqtiExtensionPackage.lifecycleEvent(this, eventType);
        }
    }

    //-------------------------------------------------------------------
    // Initialization

    /**
     * Resets the current {@link ItemSessionState} then sets all explicitly-defined
     * (valid) variables to their default values, and the built-in variables to their
     * initial values. Interactions supporting shuffling will have their random orders
     * selected.
     * <p>
     * Preconditions: None. This can be called at any time; any existing state will
     *   be wiped.
     * <p>
     * Postconditions: All explicitly-defined variables are reset to their default values.
     *   Built-in variables are set to initial values. Interaction shuffle orders will
     *   have been chosen. {@link ItemSessionState#isInitialized()} will return true.
     *
     * @param timestamp for this operation.
     */
    public void initialize(final Date timestamp) {
        Assert.notNull(timestamp);
        logger.debug("Initializing item {}", item.getSystemId());

        /* Reset all state */
        itemSessionState.reset();
        initTemplateVariables();
        initResponseState();
        initOutcomeVariables();
        itemSessionState.setSessionStatus(SessionStatus.INITIAL);
        itemSessionState.setInitialized(true);

        /* Shuffle interactions */
        shuffleInteractions();

        /* Update closed status. (This normally won't do anything, but a pathological
         * question might have completionStatus having a default value of completed!
         */
        updateClosedStatus(timestamp);
    }

    private void shuffleInteractions() {
        for (final Interaction interaction : itemProcessingMap.getInteractions()) {
            if (interaction instanceof Shuffleable<?>) {
                shuffleInteraction((Shuffleable<?>) interaction, interaction.getResponseIdentifier());
            }
        }
    }

    private <C extends Choice> void shuffleInteraction(final Shuffleable<C> interaction, final Identifier responseIdentfier) {
        if (interaction.getShuffle()) {
            final List<List<C>> choiceLists = interaction.computeShuffleableChoices();
            final List<Identifier> choiceIdentifiers = new ArrayList<Identifier>();
            for (final List<C> choiceList : choiceLists) {
                final List<Identifier> shuffleableChoiceIdentifiers = new ArrayList<Identifier>();

                /* Build up sortable identifiers */
                for (int i = 0; i < choiceList.size(); i++) {
                    final C choice = choiceList.get(i);
                    if (!choice.getFixed()) {
                        shuffleableChoiceIdentifiers.add(choice.getIdentifier());
                    }
                }

                /* Perform shuffle */
                Collections.shuffle(shuffleableChoiceIdentifiers);

                /* Then merge fixed identifiers back in */
                for (int i = 0, sortedIndex = 0; i < choiceList.size(); i++) {
                    final C choice = choiceList.get(i);
                    if (choice.getFixed()) {
                        choiceIdentifiers.add(choice.getIdentifier());
                    }
                    else {
                        choiceIdentifiers.add(shuffleableChoiceIdentifiers.get(sortedIndex++));
                    }
                }
            }
            itemSessionState.setShuffledInteractionChoiceOrder(responseIdentfier, choiceIdentifiers);
        }
        else {
            itemSessionState.setShuffledInteractionChoiceOrder(responseIdentfier, null);
        }
    }

    /**
     * Checks whether a further attempt is allowed on this item, updating
     * {@link ItemSessionState#isEnded()} as appropriate.
     * <p>
     * This must be called after every attempt and every processing run
     * to update the state.
     *
     * (New in JQTI+)
     */
    private void updateClosedStatus(final Date timestamp) {
        boolean shouldClose;
        if (item.getAdaptive()) {
            /* For adaptive items, attempts are limited by the value of the completion status variable */
            final String completionStatus = itemSessionState.getCompletionStatus();
            shouldClose = QtiConstants.COMPLETION_STATUS_COMPLETED.equals(completionStatus);
        }
        else {
            /* Non-adaptive items use maxAttempts, with 0 treated as unlimited */
            final int maxAttempts = itemSessionControllerSettings.getMaxAttempts();
            final int numAttempts = itemSessionState.getNumAttempts();
            shouldClose = (maxAttempts>0 && numAttempts>=maxAttempts);
        }
        itemSessionState.setEndTime(shouldClose ? timestamp : null);
    }

    //-------------------------------------------------------------------
    // Template processing

    /**
     * Performs Template Processing, with no <code>templateDefaults</code>, then
     * resets Response and Outcome variables to their default values.
     * <p>
     * Pre-condition: {@link ItemSessionState} must have been initialized. Candidate
     *   must not have entered the item.
     * <p>
     * Post-condition: Template variables will be initialized. Response and Outcome
     *   variables will be reset to their default values.
     *
     * @see #performTemplateProcessing(Date, List)
     */
    public void performTemplateProcessing(final Date timestamp) {
        performTemplateProcessing(timestamp, null);
    }

    /**
     * Performs Template Processing using the given <code>templateDefaults</code>,
     * then resets Response and Outcome variables to their default values.
     * <p>
     * Pre-condition: {@link ItemSessionState} must have been initialized. Candidate
     *   must not have entered the item.
     * <p>
     * Post-condition: Template variables will be initialized. Response and Outcome
     *   variables will be reset to their default values.
     *
     * @see #performTemplateProcessing(Date)
     *
     * @param templateDefaults List of {@link TemplateDefault}s, which may be null or empty.
     */
    public void performTemplateProcessing(final Date timestamp, final List<TemplateDefault> templateDefaults) {
        Assert.notNull(timestamp);
        assertNotEntered();
        logger.debug("Template processing starting on item {}", item.getSystemId());

        fireJqtiLifecycleEvent(JqtiLifecycleEventType.ITEM_TEMPLATE_PROCESSING_STARTING);
        try {
            /* Initialise template defaults with any externally provided defaults */
            if (templateDefaults != null) {
                logger.trace("Setting template default values");
                for (final TemplateDefault templateDefault : templateDefaults) {
                    final TemplateDeclaration templateDeclaration = itemProcessingMap.getValidTemplateDeclarationMap().get(templateDefault.getTemplateIdentifier());
                    if (templateDeclaration!=null) {
                        final Value defaultValue = templateDefault.evaluate(this);
                        itemSessionState.setOverriddenTemplateDefaultValue(templateDeclaration.getIdentifier(), defaultValue);
                    }
                    else {
                        fireRuntimeWarning(templateDefault, "Ignoring templateDefault '" + templateDefault.getTemplateIdentifier()
                                + "' as variable identifier is not unique");
                    }
                }
            }

            /* Perform template processing as many times as required. */
            int templateProcessingAttemptNumber = 0;
            boolean templateProcessingCompleted = false;
            while (!templateProcessingCompleted) {
                templateProcessingCompleted = doTemplateProcessingRun(++templateProcessingAttemptNumber);
            }
            if (templateProcessingAttemptNumber>1) {
                fireRuntimeInfo(item, "Template Processing was run " + templateProcessingAttemptNumber + " times");
            }

            /* Reset OVs and RVs session */
            resetOutcomeAndResponseVariables();
            updateClosedStatus(timestamp);
        }
        finally {
            fireJqtiLifecycleEvent(JqtiLifecycleEventType.ITEM_TEMPLATE_PROCESSING_FINISHED);
            logger.debug("Template processing finished on item {}", item.getSystemId());
        }
    }

    private void resetOutcomeAndResponseVariables() {
        initOutcomeVariables();
        initResponseState();
        itemSessionState.setSessionStatus(SessionStatus.INITIAL);
    }

    private boolean doTemplateProcessingRun(final int attemptNumber) {
        logger.debug("Template Processing attempt #{} starting", attemptNumber);

        /* Reset template variables */
        initTemplateVariables();

        final int maxTemplateProcessingTries = itemSessionControllerSettings.getTemplateProcessingLimit();
        if (attemptNumber > maxTemplateProcessingTries) {
            fireRuntimeWarning(item, "Exceeded maximum number " + maxTemplateProcessingTries + " of template processing retries - leaving variables at default values");
            return true;
        }

        /* Perform templateProcessing. */
        final TemplateProcessing templateProcessing = item.getTemplateProcessing();
        if (templateProcessing != null) {
            logger.trace("Evaluating template processing rules");
            try {
                for (final TemplateProcessingRule templateProcessingRule : templateProcessing.getTemplateProcessingRules()) {
                    templateProcessingRule.evaluate(this);
                }
            }
            catch (final TemplateProcessingInterrupt e) {
                switch (e.getInterruptType()) {
                    case EXIT_TEMPLATE:
                        /* Exit template processing */
                        logger.trace("Template processing interrupted by exitTemplate");
                        return true;

                    case TEMPLATE_CONSTRAINT_FAILURE:
                        /* Failed templateCondition, so try again. */
                        logger.trace("Template processing interrupted by failed templateConstraint");
                        return false;

                    default:
                        break;
                }
            }
        }
        return true;
    }


    //-------------------------------------------------------------------
    // Entry, Update, Reset and Exit

    /**
     * "Enters" the item, marking it as having been presented.
     * <p>
     * Pre-condition: Item Session must have been initialized and not already entered.
     * <p>
     * Post-condition: Item Session will have its entry time set and the duration timer
     * will start. The {@link SessionStatus} will be changed to {@link SessionStatus#PENDING_SUBMISSION}
     *
     * @param timestamp
     */
    public void enterItem(final Date timestamp) {
        Assert.notNull(timestamp);
        assertNotEntered();
        logger.debug("Entering item {}", item.getSystemId());

        /* Record entry */
        itemSessionState.setEntryTime(timestamp);

        /* Check closed status */
        updateClosedStatus(timestamp); /* (This can't change anything here but I'll keep for completeness) */

        if (!itemSessionState.isEnded()) {
            /* Update SessionStatus */
            itemSessionState.setSessionStatus(SessionStatus.PENDING_SUBMISSION);

            /* Start the duration timer */
            startItemSessionTimer(itemSessionState, timestamp);
        }
    }

    /**
     * Touches (updates) the <code>duration</code> variable in the item session if the session is
     * currently open. Call this
     * method before rendering and other operations that use rather than change the session state.
     * <p>
     * Pre-condition: Item Session must have been initialised
     * <p>
     * Post-condition: Duration variable will be updated (if the item session is currently open)
     * @param timestamp
     */
    public void touchDuration(final Date timestamp) {
        Assert.notNull(timestamp);
        assertInitialized();
        logger.debug("Touching duration for item {}", item.getSystemId());

        if (!itemSessionState.isEnded()) {
            endItemSessionTimer(itemSessionState, timestamp);
            startItemSessionTimer(itemSessionState, timestamp);
        }
    }

    /**
     * Performs a "hard" reset on an entered item session. This will reset all variables,
     * choose new interaction shuffle orders and run template processing again. The accumulated
     * duration can be reset or maintained as required.
     * <p>
     * Pre-condition: Item Session must have been entered.
     * <p>
     * Post-condition: Item Session will be reinitialized, then template processing will be
     * run again. The item's entry time will be maintained. The duration timer is reset,
     * if requested, and then restarted.
     */
    public void resetItemSessionHard(final Date timestamp, final boolean resetDuration) {
        Assert.notNull(timestamp);
        assertEntered();
        logger.debug("Performing hard reset on item session {}", item.getSystemId());

        /* Stop duration timer */
        endItemSessionTimer(itemSessionState, timestamp);

        /* Note the existing times and duration */
        final Date entryTime = itemSessionState.getEntryTime();
        final long duration = itemSessionState.getDurationAccumulated();

        /* Perform init and TP again */
        initialize(timestamp);
        performTemplateProcessing(timestamp);

        /* Save times back */
        itemSessionState.setEntryTime(entryTime);
        if (!resetDuration) {
            itemSessionState.setDurationAccumulated(duration);
        }

        if (!itemSessionState.isEnded()) {
            /* Start the duration timer again */
            startItemSessionTimer(itemSessionState, timestamp);
        }
    }

    /**
     * Resets the item session back to the state it was immediately <code>after</code> template
     * processing. Template variables and shuffled interactions will have their existing state
     * maintained. The accumulated duration can be reset or maintained as required.
     * <p>
     * Pre-condition: Item Session must have been entered.
     * <p>
     * Post-condition: Item Session will be reset back to the state it was on entry. I.e.
     * Template Variables will have the values they had after init and template processing;
     * Outcome and Response variables will be reset. The duration timer is reset, if requested,
     * and then restarted. The existing entry time will be maintained.
     */
    public void resetItemSessionSoft(final Date timestamp, final boolean resetDuration) {
        Assert.notNull(timestamp);
        assertEntered();
        logger.debug("Performing soft reset on item session {}", item.getSystemId());

        /* Stop duration timer */
        endItemSessionTimer(itemSessionState, timestamp);

        /* Maybe reset duration counter */
        if (resetDuration) {
            itemSessionState.setDurationAccumulated(0L);
        }

        /* Reset OVs and RVs */
        resetOutcomeAndResponseVariables();

        /* Check closed status */
        updateClosedStatus(timestamp);

        if (!itemSessionState.isEnded()) {
            /* Update SessionStatus */
            itemSessionState.setSessionStatus(SessionStatus.PENDING_SUBMISSION);

            /* Start the duration timer again */
            startItemSessionTimer(itemSessionState, timestamp);
        }
    }

    /**
     * Ends (closes) the item session.
     * <p>
     * Pre-condition: Item must have been entered.
     * <p>
     * Post-conditions: Item session state will be marked as ended (closed). Duration
     *   timer will be stopped.
     *
     * @param timestamp
     */
    public void endItem(final Date timestamp) {
        Assert.notNull(timestamp);
        assertEntered();
        logger.debug("Ending item {}", item.getSystemId());

        itemSessionState.setEndTime(timestamp);
        endItemSessionTimer(itemSessionState, timestamp);
    }

    /**
     * Exits the item session. (This isn't strictly needed when running single items.)
     * <p>
     * Pre-condition: Item session must have been ended (closed)
     * <p>
     * Post-condiiton: Exit time will be recorded.
     *
     * @param timestamp
     */
    public void exitItem(final Date timestamp) {
        Assert.notNull(timestamp);
        assertEnded();
        logger.debug("Exiting item {}", item.getSystemId());

        itemSessionState.setExitTime(timestamp);
    }

    //-------------------------------------------------------------------
    // Response handling

    /**
     * Binds response variables for this assessmentItem, leaving them in uncommitted state.
     * If all responses are successfully bound to variables of the required types then they
     * are additionally validated.
     * <p>
     * The set of uncommitted response variables (except those bound to {@link EndAttemptInteraction})
     * will be cleared before this runs.
     * <p>
     * Pre-condition: Item session must be open
     * <p>
     * Post-conditions: Response variables will be bound and validated, but not committed.
     *
     * @see #commitResponses(Date)
     *
     * @param timestamp timestamp for this event
     * @param responseMap Map of responses to set, keyed on response variable identifier
     * @return true if all responses were successfully bound and validated, false otherwise.
     *   Further details can be found within {@link ItemSessionState}
     *
     * @throws IllegalArgumentException if responseMap is null, contains a null value, or if
     *   any key fails to map to an interaction
     */
    public boolean bindResponses(final Date timestamp, final Map<Identifier, ResponseData> responseMap) {
        Assert.notNull(timestamp);
        Assert.notNull(responseMap, "responseMap");
        assertOpen();
        logger.debug("Binding responses {} on item {}", responseMap, item.getSystemId());

        /* Stop duration timer */
        endItemSessionTimer(itemSessionState, timestamp);

        /* Copy existing committed response values over to uncommitted ones, for possible
         * over-writing below.
         */
        for (final Entry<Identifier, Value> responseEntry : itemSessionState.getResponseValues().entrySet()) {
            final Identifier identifier = responseEntry.getKey();
            final Value value = responseEntry.getValue();
            itemSessionState.setUncommittedResponseValue(identifier, value);
        }

        /* Set all uncommitted responses bound to <endAttemptInteractions> to false initially.
         * These may be overridden for responses to the presented interactions below.
         */
        for (final Interaction interaction : itemProcessingMap.getInteractions()) {
            if (interaction instanceof EndAttemptInteraction) {
                itemSessionState.setUncommittedResponseValue(interaction, BooleanValue.FALSE);
            }
        }

        /* Save raw responses */
        itemSessionState.setRawResponseDataMap(responseMap);

        /* Now bind responses */
        final Map<Identifier, Interaction> interactionByResponseIdentifierMap = itemProcessingMap.getInteractionByResponseIdentifierMap();
        final Set<Identifier> unboundResponseIdentifiers = new HashSet<Identifier>();
        for (final Entry<Identifier, ResponseData> responseEntry : responseMap.entrySet()) {
            final Identifier responseIdentifier = responseEntry.getKey();
            final ResponseData responseData = responseEntry.getValue();
            Assert.notNull(responseData, "responseMap entry for key " + responseIdentifier);
            final Interaction interaction = interactionByResponseIdentifierMap.get(responseIdentifier);
            if (interaction != null) {
                try {
                    interaction.bindResponse(this, responseData);
                }
                catch (final ResponseBindingException e) {
                    unboundResponseIdentifiers.add(responseIdentifier);
                }
            }
            else {
                throw new IllegalArgumentException("No interaction found for response identifier " + responseIdentifier);
            }
        }

        /* Validate if all responses were successfully bound */
        final Set<Identifier> invalidResponseIdentifiers = new HashSet<Identifier>();
        if (unboundResponseIdentifiers.isEmpty()) {
            logger.debug("Validating responses");
            for (final Interaction interaction : itemProcessingMap.getInteractions()) {
                final Value responseValue = itemSessionState.getUncommittedResponseValue(interaction);
                if (!interaction.validateResponse(this, responseValue)) {
                    invalidResponseIdentifiers.add(interaction.getResponseIdentifier());
                }
            }
        }

        /* Save results */
        itemSessionState.setUnboundResponseIdentifiers(unboundResponseIdentifiers);
        itemSessionState.setInvalidResponseIdentifiers(invalidResponseIdentifiers);

        /* Touch the duration timer */
        if (!itemSessionState.isEnded()) { /* (NB: This should never fail but we'll keep it for consistency) */
            startItemSessionTimer(itemSessionState, timestamp);
        }

        return unboundResponseIdentifiers.isEmpty() && invalidResponseIdentifiers.isEmpty();
    }

    /**
     * Commits any responses previously saved via {@link #bindResponses(Date, Map)}.
     * <p>
     * NB: This is counted as an attempt until {@link #performResponseProcessing(Date)} is invoked.
     * <p>
     * Pre-condition: Item session must be open
     * <p>
     * Post-conditions: Response variables will be replaced by the uncommitted variables. The uncommitted
     * variables will be cleared. The item session state will
     * be marked as having been responded, and Session Status will be set to
     * {@link SessionStatus#PENDING_RESPONSE_PROCESSING}
     *
     * @see #bindResponses(Date, Map)
     *
     * @param timestamp timestamp for this event
     */
    public void commitResponses(final Date timestamp) {
        Assert.notNull(timestamp);
        assertOpen();
        logger.debug("Committing currently saved responses to item {}", item.getSystemId());

        /* Stop duration timer */
        endItemSessionTimer(itemSessionState, timestamp);

        /* Commit responses */
        for (final Entry<Identifier, Value> uncommittedResponseEntry : itemSessionState.getUncommittedResponseValues().entrySet()) {
            final Identifier identifier = uncommittedResponseEntry.getKey();
            final Value value = uncommittedResponseEntry.getValue();
            itemSessionState.setResponseValue(identifier, value);
        }

        /* Clear uncommitted responses */
        itemSessionState.clearUncommittedResponseValues();

        /* Update session status */
        itemSessionState.setResponded(true);
        itemSessionState.setSessionStatus(SessionStatus.PENDING_RESPONSE_PROCESSING);

        /* Touch the duration timer */
        if (!itemSessionState.isEnded()) { /* (NB: This should never fail but we'll keep it for consistency) */
            startItemSessionTimer(itemSessionState, timestamp);
        }
    }


    /**
     * Performs response processing on the <em>currently committed</em> responses,
     * changing {@link #itemSessionState} as appropriate.
     * <p>
     * Pre-condition: item session must be open.
     * <p>
     * Post-conditions: Outcome Variables will be updated. SessionStatus will be changed to
     * {@link SessionStatus#FINAL}. The <code>numAttempts</code> variables will be incremented (unless
     * directed otherwise).
     */
    public void performResponseProcessing(final Date timestamp) {
        Assert.notNull(timestamp);
        assertOpen();
        logger.debug("Response processing starting on item {}", item.getSystemId());

        fireJqtiLifecycleEvent(JqtiLifecycleEventType.ITEM_RESPONSE_PROCESSING_STARTING);
        try {
            /* Stop timer to recalculate current duration */
            endItemSessionTimer(itemSessionState, timestamp);

            /* If no responses have been committed, then set responses for all endAttemptInteractions to false now */
            if (!itemSessionState.isResponded()) {
                for (final Interaction interaction : itemProcessingMap.getInteractions()) {
                    if (interaction instanceof EndAttemptInteraction) {
                        itemSessionState.setResponseValue(interaction, BooleanValue.FALSE);
                    }
                }
            }

            /* We will always count the attempt, unless the response was to an endAttemptInteraction
             * with countAttempt set to false.
             */
            boolean countAttempt = true;
            for (final Interaction interaction : itemProcessingMap.getInteractions()) {
                if (interaction instanceof EndAttemptInteraction) {
                    final EndAttemptInteraction endAttemptInteraction = (EndAttemptInteraction) interaction;
                    final Value responseValue = itemSessionState.getResponseValue(interaction);
                    if (responseValue==null) {
                        throw new QtiCandidateStateException("Expected to find a response value for identifier " + interaction.getResponseDeclaration());
                    }
                    if (!responseValue.hasSignature(Signature.SINGLE_BOOLEAN)) {
                        fireRuntimeWarning(getSubjectItem().getResponseProcessing(),
                                "Expected value " + responseValue + " bound to endAttemptInteraction "
                                + endAttemptInteraction.getResponseIdentifier() + " to be a "
                                + Signature.SINGLE_BOOLEAN
                                + " but got " + responseValue.getSignature());
                    }
                    else if (((BooleanValue) responseValue).booleanValue()) {
                        countAttempt = !endAttemptInteraction.getCountAttempt();
                        break;
                    }
                }
            }
            if (countAttempt) {
                final int oldAttempts = itemSessionState.getNumAttempts();
                itemSessionState.setNumAttempts(oldAttempts + 1);
            }

            /* For non-adaptive items, reset outcome variables to default values */
            if (!item.getAdaptive()) {
                initOutcomeVariables();
            }

            /* Work out which RP logic to perform */
            ResponseProcessing responseProcessing = null;
            final RootNodeLookup<ResponseProcessing> resolvedResponseProcessingTemplateLookup = resolvedAssessmentItem.getResolvedResponseProcessingTemplateLookup();
            if (resolvedResponseProcessingTemplateLookup!=null) {
                responseProcessing = resolvedResponseProcessingTemplateLookup.extractAssumingSuccessful();
            }
            else {
                responseProcessing = item.getResponseProcessing();
            }

            /* Invoke response processing */
            if (responseProcessing != null) {
                responseProcessing.evaluate(this);
            }
            else {
                logger.debug("No responseProcessing rules or responseProcessing template exists, so no response processing will be performed");
            }

            /* Update final state */
            itemSessionState.setSessionStatus(SessionStatus.FINAL);
            updateClosedStatus(timestamp);

            /* Start timer again to keep calculating duration */
            if (!itemSessionState.isEnded()) {
                startItemSessionTimer(itemSessionState, timestamp);
            }
        }
        finally {
            fireJqtiLifecycleEvent(JqtiLifecycleEventType.ITEM_RESPONSE_PROCESSING_FINISHED);
            logger.debug("Response processing finished on item {}", item.getSystemId());
        }
    }

    /**
     * Resets all responses
     * <p>
     * Pre-condition: Item session must be open.
     * <p>
     * Post-condition: Responses are reset to their default values. SessionStatus is
     * reset to {@link SessionStatus#INITIAL}.
     */
    public void resetResponses(final Date timestamp) {
        Assert.notNull(timestamp);
        assertOpen();
        logger.debug("Resetting responses on item {}", item.getSystemId());

        endItemSessionTimer(itemSessionState, timestamp);
        initResponseState();
        itemSessionState.setSessionStatus(SessionStatus.INITIAL);
        updateClosedStatus(timestamp);
        if (!itemSessionState.isEnded()) {
            startItemSessionTimer(itemSessionState, timestamp);
        }
    }

    //-------------------------------------------------------------------
    // AssessmentResult generation

    public AssessmentResult computeAssessmentResult() {
        return computeAssessmentResult(new Date(), null, null);
    }

    public AssessmentResult computeAssessmentResult(final Date timestamp, final String sessionIdentifier,
            final URI sessionIdentifierSourceId) {
        final AssessmentResult result = new AssessmentResult();
        final Context context = new Context(result);
        result.setContext(context);
        if (sessionIdentifier!=null && sessionIdentifierSourceId!=null) {
            final SessionIdentifier sessionIdentifierNode = new SessionIdentifier(context);
            sessionIdentifierNode.setIdentifier(sessionIdentifier);
            sessionIdentifierNode.setSourceId(sessionIdentifierSourceId);
            context.getSessionIdentifiers().add(sessionIdentifierNode);
        }

        result.getItemResults().add(computeItemResult(result, timestamp));
        return result;
    }

    ItemResult computeItemResult(final AssessmentResult owner, final Date timestamp) {
        final ItemResult itemResult = new ItemResult(owner);
        itemResult.setIdentifier(item.getIdentifier());
        itemResult.setDateStamp(timestamp);
        itemResult.setSessionStatus(itemSessionState.getSessionStatus());
        recordItemVariables(itemResult);
        return itemResult;
    }

    private void recordItemVariables(final ItemResult result) {
        final List<ItemVariable> itemVariables = result.getItemVariables();
        itemVariables.clear();

        /* Record completionStatus */
        itemVariables.add(new OutcomeVariable(result, item.getCompletionStatusOutcomeDeclaration(), itemSessionState.getCompletionStatusValue()));

        /* Then do other OVs */
        for (final Entry<Identifier, Value> mapEntry : itemSessionState.getOutcomeValues().entrySet()) {
            final OutcomeDeclaration declaration = itemProcessingMap.getValidOutcomeDeclarationMap().get(mapEntry.getKey());
            if (declaration!=null) {
                final Value value = mapEntry.getValue();
                final OutcomeVariable variable = new OutcomeVariable(result, declaration, value);
                itemVariables.add(variable);
            }
        }

        /* Record duration & numAttempts */
        itemVariables.add(new ResponseVariable(result, item.getDurationResponseDeclaration(), itemSessionState.computeDurationValue(), null));
        itemVariables.add(new ResponseVariable(result, item.getNumAttemptsResponseDeclaration(), itemSessionState.getNumAttemptsValue(), null));

        /* Then do rest of RVs */
        final Map<Identifier, Interaction> interactionMap = itemProcessingMap.getInteractionByResponseIdentifierMap();
        for (final Entry<Identifier, Value> mapEntry : itemSessionState.getResponseValues().entrySet()) {
            final ResponseDeclaration declaration = itemProcessingMap.getValidResponseDeclarationMap().get(mapEntry.getKey());
            if (declaration!=null) {
                final Value value = mapEntry.getValue();
                List<Identifier> interactionChoiceOrder = null;
                final Interaction interaction = interactionMap.get(declaration.getIdentifier());
                if (interaction != null && interaction instanceof Shuffleable) {
                    interactionChoiceOrder = itemSessionState.getShuffledInteractionChoiceOrder(interaction);
                }
                final ResponseVariable variable = new ResponseVariable(result, declaration, value, interactionChoiceOrder);
                itemVariables.add(variable);
            }
        }

        /* Then do TVs */
        for (final Entry<Identifier, Value> mapEntry : itemSessionState.getTemplateValues().entrySet()) {
            final TemplateDeclaration declaration = itemProcessingMap.getValidTemplateDeclarationMap().get(mapEntry.getKey());
            if (declaration!=null) {
                final Value value = mapEntry.getValue();
                final TemplateVariable variable = new TemplateVariable(result, declaration, value);
                itemVariables.add(variable);
            }
        }
    }

    //-------------------------------------------------------------------
    // Internal management

    private void startItemSessionTimer(final ItemSessionState itemSessionState, final Date timestamp) {
        itemSessionState.setDurationIntervalStartTime(timestamp);
    }

    private void endItemSessionTimer(final ItemSessionState itemSessionState, final Date timestamp) {
        final long durationDelta = timestamp.getTime() - itemSessionState.getDurationIntervalStartTime().getTime();
        itemSessionState.setDurationAccumulated(itemSessionState.getDurationAccumulated() + durationDelta);
        itemSessionState.setDurationIntervalStartTime(null);
    }

    private void initTemplateVariables() {
        for (final TemplateDeclaration templateDeclaration : itemProcessingMap.getValidTemplateDeclarationMap().values()) {
            initValue(templateDeclaration);
        }
    }

    private void initResponseState() {
        for (final ResponseDeclaration responseDeclaration : itemProcessingMap.getValidResponseDeclarationMap().values()) {
            if (!VariableDeclaration.isReservedIdentifier(responseDeclaration.getIdentifier())) {
                initValue(responseDeclaration);
            }
        }
        itemSessionState.clearUncommittedResponseValues();
        itemSessionState.clearRawResponseDataMap();
        itemSessionState.clearUnboundResponseIdentifiers();
        itemSessionState.clearInvalidResponseIdentifiers();
        itemSessionState.setNumAttempts(0);
        itemSessionState.setResponded(false);
    }

    private void initOutcomeVariables() {
        for (final OutcomeDeclaration outcomeDeclaration : itemProcessingMap.getValidOutcomeDeclarationMap().values()) {
            if (!VariableDeclaration.isReservedIdentifier(outcomeDeclaration.getIdentifier())) {
                initValue(outcomeDeclaration);
            }
        }
        itemSessionState.setCompletionStatus(QtiConstants.COMPLETION_STATUS_UNKNOWN);
    }

    private void initValue(final VariableDeclaration declaration) {
        Assert.notNull(declaration);
        setVariableValue(declaration, computeInitialValue(declaration));
    }

    private Value computeInitialValue(final VariableDeclaration declaration) {
        Assert.notNull(declaration);
        return computeInitialValue(declaration.getIdentifier());
    }

    private Value computeInitialValue(final Identifier identifier) {
        return computeDefaultValue(identifier);
    }

    //-------------------------------------------------------------------

    private void assertNotExited() {
        if (itemSessionState.isExited()) {
            throw new QtiCandidateStateException("Item session has been exited so is no longer available");
        }
    }

    private void assertInitialized() {
        assertNotExited();
        if (!itemSessionState.isInitialized()) {
            throw new QtiCandidateStateException("Item session has not been initialized");
        }
    }

    private void assertNotEntered() {
        assertInitialized();
        if (itemSessionState.isEntered()) {
            throw new QtiCandidateStateException("Item session has already been entered");
        }
    }

    private void assertEntered() {
        assertInitialized();
        if (!itemSessionState.isEntered()) {
            throw new QtiCandidateStateException("Item session has not been entered");
        }
    }

    private void assertOpen() {
        assertEntered();
        if (itemSessionState.isEnded()) {
            throw new QtiCandidateStateException("Item session has ended");
        }
    }

    private void assertEnded() {
        assertInitialized();
        if (!itemSessionState.isEnded()) {
            throw new QtiCandidateStateException("Item session has not been ended");
        }
    }

    //-------------------------------------------------------------------

    @Override
    public String toString() {
        return getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(this))
                + "(resolvedAssessmentItem=" + resolvedAssessmentItem
                + ",itemSessionState=" + itemSessionState
                + ")";
    }
}
