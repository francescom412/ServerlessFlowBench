exports.lambdaHandler = function (event, context, callback) {

    let sentence;
    let languageCode;

    // search for string and language in request
    if (event.queryStringParameters && event.queryStringParameters.sentence) {
        sentence = event.queryStringParameters.sentence;
    } else if (event.sentence) {
        sentence = event.sentence;
    } else {
        callback(null, "Error");
    }
    if (event.queryStringParameters && event.queryStringParameters.language_code) {
        languageCode = event.queryStringParameters.language_code;
    } else if (event.language_code) {
        languageCode = event.language_code;
    } else {
        callback(null, "Error");
    }

    translateText(sentence, languageCode, callback);
}

function translateText(text, sourceLanguageCode, callback) {

    // prepare request
    const AWS = require('aws-sdk');
    const client = new AWS.Translate();

    const request = {
        SourceLanguageCode: sourceLanguageCode,
        TargetLanguageCode: 'en',
        Text: text
    };

    // perform request
    client.translateText(request, function (err, response) {
        if (err) {
            callback(null, "Error");
        } else {
            // return result
            const ret = {
                original_sentence: text,
                sentence: response.TranslatedText
            };
            callback(null, ret);
        }
    });
}