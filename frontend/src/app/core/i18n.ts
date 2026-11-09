import { Injectable, signal } from '@angular/core';

/**
 * English and Hindi for the visitor pages. Waiting rooms in India are used by people on cheap phones in many languages;
 * the console stays English.
 */
const EN = {
  checking: 'Checking your browser',
  checkingHint: 'A few seconds of work your phone does once, so bots pay for every place they take. Keep this tab open.',
  lobbyTitle: "You're in the lobby",
  lobbyHint: "Everyone who arrives before the sale opens gets a place by a fair lottery. Arriving earlier doesn't help, so there's no need to refresh.",
  opensIn: 'Sale opens in',
  lobbyNumber: 'Your lobby number',
  inLobby: 'people in the lobby',
  drawing: 'Drawing the lottery…',
  inLine: "You're in line",
  yourPlace: 'Your place in line',
  ahead: 'ahead of you',
  eta: 'Estimated wait',
  letIn: 'Letting in about {n} people a minute',
  keepOpen: "Keep this tab open. We'll take you to the site when it's your turn — you don't need to refresh.",
  yourTurn: "It's your turn",
  yourTurnHint: 'You have a pass to shop. Go ahead before it expires.',
  continue: 'Continue to the site',
  passExpires: 'Pass expires in',
  expired: 'Your pass expired',
  expiredHint: 'Your turn came and went. Join again to get a new place at the back of the line.',
  joinAgain: 'Join again',
  soldOut: 'Sold out',
  soldOutHint: "Everything has been sold. We're sorry — thank you for waiting fairly.",
  closed: 'This sale is closed',
  paused: 'Paused for a moment',
  pausedHint: "The organiser has paused entry. You keep your place; we'll carry on automatically.",
  scheduled: 'The lobby opens soon',
  scheduledHint: 'Come back when the lobby opens. Everyone who arrives in the lobby gets an equal chance.',
  lobbyOpensIn: 'Lobby opens in',
  joinLobby: 'Join the lobby',
  verify: 'Check the lottery yourself',
  verifyHint: 'The lottery seed was committed before anyone joined.',
  reconnecting: 'Connection lost — retrying…',
  fair: 'Fair by design',
  noRefresh: 'No refresh needed',
  oneTab: 'One tab is enough',
  notFound: "We couldn't find this sale",
  rateLimited: 'Too many people joined from your network. Trying again in a minute.',
  progress: 'checks done',
};
export type Strings = typeof EN;

const HI: Strings = {
  checking: 'आपके ब्राउज़र की जाँच हो रही है',
  checkingHint: 'आपका फ़ोन एक बार कुछ सेकंड काम करता है, ताकि बॉट को हर जगह की कीमत चुकानी पड़े। यह टैब खुला रखें।',
  lobbyTitle: 'आप लॉबी में हैं',
  lobbyHint: 'बिक्री शुरू होने से पहले आने वाले सभी लोगों को निष्पक्ष लॉटरी से जगह मिलती है। पहले आने से कोई फ़ायदा नहीं, इसलिए रिफ़्रेश करने की ज़रूरत नहीं।',
  opensIn: 'बिक्री शुरू होने में',
  lobbyNumber: 'आपका लॉबी नंबर',
  inLobby: 'लोग लॉबी में',
  drawing: 'लॉटरी निकाली जा रही है…',
  inLine: 'आप कतार में हैं',
  yourPlace: 'कतार में आपकी जगह',
  ahead: 'आपसे आगे',
  eta: 'अनुमानित इंतज़ार',
  letIn: 'हर मिनट लगभग {n} लोगों को अंदर भेजा जा रहा है',
  keepOpen: 'यह टैब खुला रखें। आपकी बारी आने पर हम आपको साइट पर ले जाएँगे — रिफ़्रेश करने की ज़रूरत नहीं।',
  yourTurn: 'आपकी बारी आ गई',
  yourTurnHint: 'आपके पास ख़रीदारी का पास है। इसकी समय-सीमा ख़त्म होने से पहले आगे बढ़ें।',
  continue: 'साइट पर जाएँ',
  passExpires: 'पास की समय-सीमा',
  expired: 'आपका पास समाप्त हो गया',
  expiredHint: 'आपकी बारी निकल गई। कतार के अंत में नई जगह पाने के लिए फिर से जुड़ें।',
  joinAgain: 'फिर से जुड़ें',
  soldOut: 'सब बिक गया',
  soldOutHint: 'सब कुछ बिक चुका है। हमें खेद है — निष्पक्ष रूप से इंतज़ार करने के लिए धन्यवाद।',
  closed: 'यह बिक्री बंद है',
  paused: 'थोड़ी देर के लिए रोका गया',
  pausedHint: 'आयोजक ने प्रवेश रोका है। आपकी जगह सुरक्षित है; हम अपने-आप आगे बढ़ेंगे।',
  scheduled: 'लॉबी जल्द खुलेगी',
  scheduledHint: 'लॉबी खुलने पर वापस आएँ। लॉबी में आने वाले हर व्यक्ति को बराबर मौका मिलता है।',
  lobbyOpensIn: 'लॉबी खुलने में',
  joinLobby: 'लॉबी में जुड़ें',
  verify: 'लॉटरी ख़ुद जाँचें',
  verifyHint: 'लॉटरी का सीड किसी के जुड़ने से पहले तय कर दिया गया था।',
  reconnecting: 'कनेक्शन टूट गया — फिर से कोशिश हो रही है…',
  fair: 'निष्पक्ष व्यवस्था',
  noRefresh: 'रिफ़्रेश की ज़रूरत नहीं',
  oneTab: 'एक टैब काफ़ी है',
  notFound: 'यह बिक्री नहीं मिली',
  rateLimited: 'आपके नेटवर्क से बहुत लोग जुड़े। एक मिनट में फिर कोशिश होगी।',
  progress: 'जाँचें पूरी',
};

@Injectable({ providedIn: 'root' })
export class I18n {
  readonly lang = signal<'en' | 'hi'>(localStorage.getItem('foyer.lang') === 'hi' ? 'hi' : 'en');

  t(): Strings {
    return this.lang() === 'hi' ? HI : EN;
  }

  set(lang: 'en' | 'hi') {
    localStorage.setItem('foyer.lang', lang);
    document.documentElement.lang = lang;
    this.lang.set(lang);
  }
}
