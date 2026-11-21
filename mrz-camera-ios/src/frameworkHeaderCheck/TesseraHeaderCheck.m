// Compiled (syntax only, never linked or shipped) against the generated header of the Tessera
// framework by the checkTesseraFrameworkHeader Gradle task. Each line names a public type or member
// that Swift apps reach through the umbrella; one that drops out of the export list, or is renamed,
// fails the compile. Add a line when a module joins the umbrella.
#import <Tessera/Tessera.h>
#import <CoreNFC/CoreNFC.h>

// Camera and MRZ (mrz-camera-ios, mrz-camera-core, mrz-core, types).
static void tesseraCameraTypes(TesseraAVCaptureMrzScanner *scanner, TesseraMrzParser *parser) {
    (void)scanner;
    (void)parser;
}

// Chip reading (emrtd-core, emrtd-nfc-ios, types).
static void tesseraChipTypes(id<NFCISO7816Tag> tag) {
    TesseraCoreNfcSession *session = [[TesseraCoreNfcSession alloc] initWithAlertMessage:@"text"];
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
