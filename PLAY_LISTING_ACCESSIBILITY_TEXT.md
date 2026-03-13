# Play Listing Text for Accessibility API Compliance

Use this copy in Google Play Console so Accessibility API usage is clearly documented in your listing.

## Short Description (80 chars max)
IRB-approved research app for encrypted screenshot and smartphone usage data.

## Full Description (Accessibility section to include)
MindPulse is an IRB-approved University of Wisconsin-Madison research app for consented study participants.

The app can collect encrypted screenshots, app usage data, and related study metadata when participants enable data capture in the app.

Accessibility API disclosure:

MindPulse uses Android AccessibilityService API to capture encrypted screenshots for approved research data collection.

DATA COLLECTED VIA ACCESSIBILITYSERVICE:

Because screenshots capture everything visible on screen, the following data types may be collected:

- Web browsing history
- Emails
- SMS or MMS messages
- Other in-app messages
- Precise location
- Personal identifiers (name, email, address, phone number)
- Race and ethnicity; political or religious beliefs
- Sexual orientation or gender identity
- Financial information (credit/debit/bank accounts, purchases)
- Health and fitness information
- Photos, videos, voice/sound recordings, music, files, documents
- Calendar events and contacts
- Page views, taps, in-app search history
- Installed apps and other user-generated content
- Device or other identifiers

ADDITIONAL DATA COLLECTED:

- App usage metadata (active app name, timestamps)
- Device diagnostic logs (for research quality assurance)
- Location data (GPS coordinates collected alongside screenshots)

PURPOSE:
This data is used exclusively for approved academic research on smartphone use and digital behavior at UW-Madison.

SECURITY:
All data is encrypted on device and transmitted securely to authorized UW-Madison research systems.

Participation is voluntary, and participants can stop or disable capture at any time.

Privacy and participant rights are described in the app privacy policy.

## Reviewer Notes (for Play Console declaration/support notes)
- AccessibilityService is used only for participant-authorized research screenshot capture.
- Prominent in-app disclosure dialog appears BEFORE opening Accessibility settings.
- Disclosure dialog title: "Accessibility API Data Disclosure"
- All data types (including web browsing history, emails, SMS/MMS messages, other in-app messages) are listed as individual bullet points in the disclosure.
- User must tap "Agree and Open Settings" / "Agree and Continue" to proceed. Cancel/decline path is supported.
- The disclosure dialog is non-dismissable (setCancelable=false) -- user must make an explicit choice.
- The accessibility_capture_service_description (shown in Android Accessibility Settings) also lists all data types.
- Three places where disclosure appears:
  1. MainActivity: when capture switch triggers accessibility mode
  2. ScreenLifeFragment: when user manually enables accessibility capture
  3. Android Accessibility Settings: service description string
