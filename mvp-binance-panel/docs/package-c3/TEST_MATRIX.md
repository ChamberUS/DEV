# Package C3 — verification matrix

New focused JUnit coverage: 31 model/observer/lifecycle tests, 17 native widget tests, six actual PanelApp isolation tests (54 total). This inventory supplements the fresh full-suite XML/results, not the historical C2 baseline.

| Required case | Evidence |
|---|---|
| 1. Real event publication | actualAbsentServiceProbePublishesHonestUnavailability; monitorPublishesThroughCapturedObserverOnItsExistingWorker |
| 2. Ordering | timestampsOrderNewestFirstRegardlessOfArrival; equalTimestampOrderIsPredictable |
| 3. Stable IDs | stableIdentitySurvivesReadAndAggregation |
| 4. Duplicate suppression | duplicateIdentityIsRejectedAcrossEventTypes; deduplicationRemembersEvictedRowsWithinBoundedWindow |
| 5. Rate limiting | reconnectStormAggregatesBothDirections; repeatedFailedPollsDoNotCreateNotificationsOrUnreadAgain |
| 6. Retention | retentionEvictsOldestAt100 |
| 7–8. Read/unread, all read | readUnreadAndAllReadOnlyChangePresentation; markOneReadAndUnreadButtonsChangeTheSameEvent; markAllUsesLocalReadStateOnly |
| 9–11. Empty/offline/unavailable | noEventsAreCreatedByStartingASession; emptyAndSourceAvailabilityAreDifferentStates; timeoutIsUncertainNeverConfirmedDisconnectOrOperationFailure; source-less legacy tests retained |
| 12. Roles | userCannotIngestAdminEvents; sameAccountRoleDemotionClearsAdminPresentation; userGateFailureDoesNotDiscloseAdminEvents |
| 13–15. Session/logout/stale callbacks | sameAccountReloginIsANewPrivateHistory; accountReplacementRejectsOldCallbacks; actualAuthLogoutImmediatelyClearsPrivateEvents; delayedMonitorDeliveryRetainsTheOldOwnerAcrossRestart; staleObserverCannotMutateNewSessionAvailability |
| 16–18. Research/allowed/rejected navigation | notificationResearchDestinationUsesRealPendingMfaGate; enterUsesAllowlistedDestinationThroughExistingRouter; unauthorizedResearchDeepLinkCannotBypassRouter; existing ResearchGateReproductionTest unchanged |
| 19. EN/PT | localeSwitchUpdatesOpenCenterWithoutDuplicatingEvents; allNotificationKeysHaveLanguageParityAndNamedParameterParity; PackageC1CatalogTest unchanged |
| 20. Themes | bothThemesPreserveSelectionIdentityAndReadState; descriptionsWrapWithoutHorizontalScrollInBothThemesAndLanguages; native screenshot matrix |
| 21–24. Motion/focus/keyboard/accessibility | popoverOpenCloseRestoresFocusWithoutMascotLoading (FULL/REDUCED/OFF); Enter/ESC tests; bellUnreadIndicatorHasGenuineAccessibleCount; viewAnnouncesSeverityInWordsAndHidesRawPayload |
| 25–26. Lifecycle/listeners | repeatedNavigationKeepsOneSurfaceAndDetachesEveryClosedView; cachedHiddenViewUnsubscribesAndReattachesWithoutHistoryLoss; disposableSessionObserverDoesNotAccumulateAfterClose; disposedPopoverCannotSubscribeOrOpenAgain |
| 27. No fabricated DEFAULT events | noEventsAreCreatedByStartingASession; firstConnectedProbeIsNotARecoveryNotification; initialAbsenceDoesNotImplyLostConnectionOrRestored; packaging excludes all QA authority/harness classes |
| 28. Privacy | securityRejectionsAreCountedWithoutPollSpam; contractErrorsAreHonestAndBounded; genericRealGateFailurePublishesOnlyForAdmin; closed event vocabulary accepts no arbitrary payload/parameters |

Native C3 flow uses the actual PanelApp, synthetic test-only authority, one actual absent-Service probe and controlled read-only status fixtures. Five representative center states at 1920×1080, 1440×900 and 1100×700, both languages/themes: 60 final screenshots. OFF 173 checks, FULL 133, REDUCED 133, all PASS. Fixtures never enter the production artifact. Existing full Panel regression remains mandatory.

AT labels/roles and accessible text-change notifications are tested through JavaFX. An actual VoiceOver session is a human UAT step; automated property checks are not an end-to-end screen-reader certification.
