const AWS = require("aws-sdk");
const { v4: uuidv4 } = require("uuid");

const dynamoDb = new AWS.DynamoDB.DocumentClient();

exports.handler = async (event, payInfo) => {
    const userId = event;
    const payLoad = payInfo
    const id = uuidv4();
    const createdAt = new Date().toISOString();

    const params = {
        TableName: "Events",
        Item: {
            id,
            userId,
            createdAt,
            payLoad
        }
    };

    try {
        await dynamoDb.put(params).promise();
        return { id, userId, createdAt, payLoad };
    } catch (error) {
        console.error("Error creating event:", error);
        throw new Error("Failed to create event");
    }
};

