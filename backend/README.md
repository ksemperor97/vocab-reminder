# Backend

Firebase backend workspace.

M0 includes a buildable TypeScript Functions package with a health endpoint and dictionary proxy stub. M2 adds Firestore security rules, emulator tests, and real dictionary proxy behavior.

```sh
cd functions
npm install
npm run build
npm test
```

Firestore rules tests run through the Firebase emulator and require Java 21+:

```sh
cd functions
npm run emulators:rules
```

No Firebase project ID or secret is stored in this repository.
