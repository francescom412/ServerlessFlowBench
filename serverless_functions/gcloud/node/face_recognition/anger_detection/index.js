exports.gcFunctionsHandler = function (req, res) {

    let url;

    if (req.query && req.query.url) {
        url = req.query.url;
    } else if (req.url) {
        url = req.url;
    } else {
        res.send("Error");
    }

    const request = require('request').defaults({ encoding: null });
    request.get(url, function (error, response, body) {
        if (!error && response.statusCode === 200) {
            const image = Buffer.from(body).toString('base64');
            detectAnger(image, url, res);
        } else {
            res.send("Error");
        }
    });

}

function detectAnger(image, url, res) {

    const vision = require('@google-cloud/vision');
    const client = new vision.ImageAnnotatorClient();

    const request = {
        "image": {
            "content": image
        },
        "features": [
            {
                "type": "FACE_DETECTION"
            }
        ],
    };

    client.annotateImage(request).then((result) => {

        result = JSON.parse(JSON.stringify(result));
        const faces = result[0].faceAnnotations;

        faces.forEach((face) => {
            if (face.angerLikelihood === "VERY_LIKELY" || face.angerLikelihood === "LIKELY") {
                res.send("True");
            }
        });

        res.send("False");

    }).catch((err) => {res.send("Error");});
}