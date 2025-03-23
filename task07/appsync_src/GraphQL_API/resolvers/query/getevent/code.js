const AWS = require("aws-sdk");

const dynamoDB = new AWS.DynamoDB.DocumentClient();
const TABLE_NAME = process.env.EVENTS_TABLE;

exports.handler = async (event) => {
    console.log("Received event:", JSON.stringify(event, null, 2));

    try {
        const { id } = event.arguments; // Отримуємо id з GraphQL-запиту

        if (!id) {
            throw new Error("Missing event ID");
        }

        const params = {
            TableName: TABLE_NAME,
            Key: { id },
        };

        const result = await dynamoDB.get(params).promise();

        if (!result.Item) {
            throw new Error(`Event with ID ${id} not found`);
        }

        return result.Item;
    } catch (error) {
        console.error("Error fetching event:", error);
        throw new Error(error.message);
    }
};