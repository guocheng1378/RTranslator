If you want to early test this version of the app, first of all consider that this is a development preview, so it could be pretty unstable, and it will require initial setup, so to try it you need to:

Download from [here](https://github.com/niedev/OnnxModelsEnhancer/releases/tag/v1.0.0-beta) the Mozilla.zip, 
Madlad.zip, HY-MT.zip. After the download extract these folders (their name should remain "Mozilla", "Madlad" and "HY-MT", with the content of these .zip directly inside the corresponding extracted folders, if you change the structure of these folder the app will not work).

After that create a folder named "models", inside it create a folder named "Translation", and inside it paste all the extracted folders.

Now download one of the apk in this folder, install it, open the app, enable the requested file access and start the download.

After the download has finished, exit the app and enable all its permissions from the Android Settings (Settings -> Applications -> RTranslator).

Then re open the app and everything should work.

By default, the models used for translation are the Mozilla ones, to select the other supported model you can select one from RTranslator's settings, at the bottom (note that probably, to execute Madlad and HY-MT, you will need a phone with at least 12GB of RAM, if the RAM won't be enough the app will crash until you wipe its data).
