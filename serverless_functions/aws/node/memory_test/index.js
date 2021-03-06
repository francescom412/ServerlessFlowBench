exports.lambdaHandler = function (event, context, callback) {

    let n;

    // search for array dimension in request
    // noinspection JSUnresolvedVariable
    if (event.queryStringParameters && event.queryStringParameters.hasOwnProperty('n')) {
        n = event.queryStringParameters.n;
    } else if (event.hasOwnProperty('n')) {
        n = event.n;
    } else {
        n = 1100000;
    }
    // check value
    if (n <= 0) {
        n = 1100000;
    }

    // measure computation
    let startTime = Date.now();
    memoryStress(n);
    let endTime = Date.now();
    let executionTime = (endTime - startTime);

    // response creation
    const ret = {
        statusCode: 200,
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            success: true,
            payload: {
                'test': 'memory_test',
                'dimension': n,
                'milliseconds': executionTime
            }
        })
    };
    callback(null, ret);
}

function memoryStress(n) {
    // dynamically append elements to a list
    // noinspection JSMismatchedCollectionQueryUpdate
    let memoryList = [];
    for (let i = 0; i < n; i++) {
        memoryList.push(i);
    }
}