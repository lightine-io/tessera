// Compiled (syntax only, never linked or shipped) against the generated header of the Tessera
// framework by the checkTesseraFrameworkHeader Gradle task. Each line names a public type or member
// that Swift apps reach through the umbrella; one that drops out of the export list, or is renamed,
// fails the compile. Add a line when a module joins the umbrella.
#import <Tessera/Tessera.h>
#import <CoreNFC/CoreNFC.h>

// Camera and MRZ (mrz-camera-ios, mrz-ocr-core, mrz-core, types).
static void tesseraCameraTypes(TesseraAVCaptureMrzScanner *scanner, TesseraMrzParser *parser) {
    (void)scanner;
    (void)parser;
}

// Chip reading (emrtd-core, emrtd-nfc-ios, types).
static void tesseraChipTypes(id<NFCISO7816Tag> tag) {
    TesseraCoreNfcSession *session = [[TesseraCoreNfcSession alloc] initWithAlertMessage:@"text" failureMessage:@"text"];
    [session connectWithCompletionHandler:^(TesseraCoreNfcTransport *transport, NSError *error) {
        id<TesseraApduTransport> seam = transport;
        (void)seam.maxCommandLength;
        (void)transport.supportsPace;
        (void)error;
    }];
    [session endErrorMessage:@"text"];
    [session end];
    TesseraCoreNfcTransport *own = [[TesseraCoreNfcTransport alloc] initWithTag:tag];
    [own close];
    BOOL available = TesseraCoreNfcSessionCompanion.shared.isReadingAvailable;
    NSString *aid = TesseraCoreNfcSessionCompanion.shared.EMRTD_APPLICATION_ID;
    (void)available;
    (void)aid;

    TesseraNfcError *lost = TesseraNfcTagLost.shared;
    TesseraNfcTimeout *timeout = nil;
    TesseraNfcUnavailableReason *cancelled = TesseraNfcUnavailableReason.cancelledByUser;
    TesseraNfcUnavailableReason *busy = TesseraNfcUnavailableReason.busy;
    TesseraApduTransportException *failure = nil;
    TesseraChipAccessKeyMrz *mrzKey = nil;
    TesseraChipAccessKeyCan *canKey = nil;
    (void)lost;
    (void)timeout;
    (void)cancelled;
    (void)busy;
    (void)failure;
    (void)mrzKey;
    (void)canKey;
}

// The key from a document, the options builder, roots, codes (emrtd-core, types).
static void tesseraEasyApi(TesseraMrzDocument *document, TesseraParseResult *result, TesseraKotlinByteArray *bytes,
                           TesseraChipError *error, TesseraChipCheckKind *kind) {
    TesseraMrzKeyResult *fromDocument = [TesseraChipAccessKeyMrzCompanion.shared fromDocument:document];
    TesseraMrzKeyResult *fromResult = [TesseraChipAccessKeyMrzCompanion.shared fromResult:result];
    TesseraMrzKeyResult *typed = [TesseraChipAccessKeyMrzCompanion.shared fromTypedFieldsDocumentNumber:@"" dateOfBirth:@"" dateOfExpiry:@""];
    TesseraChipAccessKeyCan *can = [[TesseraChipAccessKeyCan alloc] initWithCan:@"123456" error:nil];
    TesseraCanKeyResult *canResult = [TesseraChipAccessKeyCanCompanion.shared fromCan:@"123456"];
    TesseraNormalizedMrzFields *normalized = [TesseraChipAccessKeyMrzCompanion.shared normalizeTypedFieldsDocumentNumber:@" t1 "
                                                                                                           dateOfBirth:@"900315"
                                                                                                          dateOfExpiry:@"340314"];
    NSSet<TesseraMrzField *> *changedFields = normalized.changedFields;
    TesseraChipReadOptions *options = [[[[TesseraChipReadOptionsBuilder alloc] initWithAccessKey:can]
        dataGroupsNumbers:[NSSet setWithObjects:@(1), @(2), nil]] buildAndReturnError:nil];
    TesseraChipReadOptions *withPrinted = [[[[[TesseraChipReadOptionsBuilder alloc] initWithAccessKey:can]
        dataGroupsNumberCollection:@[ @(1), @(2) ]] printedDocumentDocument:document] buildAndReturnError:nil];
    TesseraTrustAnchorLoad *roots = [TesseraTrustAnchors.shared loadBytes:bytes heldRoots:@[]];
    TesseraTrustAnchorLoad *more = [TesseraTrustAnchors.shared loadPath:@"roots.ml" heldRoots:@[]];
    TesseraTrustAnchorLoad *both = [roots plusOther:more];
    NSString *code = error.code;
    TesseraInt *statusWord = error.statusWord;
    NSString *checkCode = kind.code;
    (void)fromDocument;
    (void)fromResult;
    (void)typed;
    (void)canResult;
    (void)changedFields;
    (void)withPrinted;
    (void)options;
    (void)both;
    (void)code;
    (void)statusWord;
    (void)checkCode;
}

// The read that owns the whole session (emrtd-nfc-ios, emrtd-core).
static void tesseraChipReadTypes(TesseraChipReadOptions *options, id<TesseraChipReadObserver> observer) {
    id<TesseraChipReadHandle> shortest = [TesseraCoreNfcChipRead.companion startOptions:options observer:observer];
    TesseraCoreNfcReadSettings *defaults = [[TesseraCoreNfcReadSettings alloc] init];
    TesseraLinkLossPolicyResume *resume = [[TesseraLinkLossPolicyResume alloc] initWithMaxRestarts:2 error:nil];
    TesseraCoreNfcReadSettings *settings = [[TesseraCoreNfcReadSettings alloc] initWithLinkLoss:resume
                                                                             settleDelayMillis:400
                                                                          commandTimeoutMillis:5000
                                                                      waitAfterSystemEndMillis:1000
                                                                                     telemetry:nil
                                                                                    observerOn:TesseraObserverThread.main
                                                                                         error:nil];
    TesseraCoreNfcReadSettings *built = [[[[[[TesseraCoreNfcReadSettingsBuilder alloc] init]
        linkLossPolicy:[TesseraLinkLossPolicyCompanion.shared readAgainMaxRestarts:1 error:nil]] telemetrySink:nil]
        observerOnThread:TesseraObserverThread.caller] buildAndReturnError:nil];
    TesseraCoreNfcReadSettings *changed = [[settings.toBuilder settleDelayMillisMillis:0] buildAndReturnError:nil];
    TesseraLinkLossPolicy *noRestart = TesseraLinkLossPolicyCompanion.shared.end;
    TesseraLinkLossPolicy *resumeTwice = [TesseraLinkLossPolicyCompanion.shared resumeMaxRestarts:2 error:nil];
    TesseraNfcUnavailableReason *unavailable = TesseraCoreNfcChipRead.companion.availability;
    (void)built;
    (void)changed;
    (void)noRestart;
    (void)resumeTwice;
    (void)unavailable;
    id<TesseraCoreNfcSheetTexts> texts = TesseraCoreNfcDefaultSheetTexts.shared;
    id<TesseraChipReadHandle> full = [TesseraCoreNfcChipRead.companion startOptions:options texts:texts settings:settings observer:observer];
    id<TesseraChipReadHandle> withTexts = [TesseraCoreNfcChipRead.companion startOptions:options texts:texts observer:observer];
    [full cancel];
    BOOL ended = shortest.isEnded;
    (void)ended;
    (void)defaults;
    (void)withTexts;
}
